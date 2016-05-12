/*
 *  Copyright (c) 2016, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.wso2.carbon.uuf.internal;

import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.carbon.uuf.api.HttpRequest;
import org.wso2.carbon.uuf.core.App;
import org.wso2.carbon.uuf.core.RequestLookup;
import org.wso2.carbon.uuf.exception.FragmentNotFoundException;
import org.wso2.carbon.uuf.exception.HttpErrorException;
import org.wso2.carbon.uuf.exception.PageNotFoundException;
import org.wso2.carbon.uuf.exception.PageRedirectException;
import org.wso2.carbon.uuf.exception.UUFException;
import org.wso2.carbon.uuf.internal.core.create.AppCreator;
import org.wso2.carbon.uuf.internal.core.create.AppDiscoverer;
import org.wso2.carbon.uuf.internal.io.StaticResolver;
import org.wso2.carbon.uuf.internal.util.MimeMapper;
import org.wso2.carbon.uuf.internal.util.RequestUtil;

import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class UUFRegistry {

    private static final Logger log = LoggerFactory.getLogger(UUFRegistry.class);

    private final Map<String, App> apps;
    private final StaticResolver staticResolver;
    private final DebugAppender debugAppender;

    public UUFRegistry(AppDiscoverer appDiscoverer, AppCreator appCreator, StaticResolver staticResolver,
                       DebugAppender debugAppender) {
        this.apps = loadApps(appDiscoverer, appCreator);
        this.staticResolver = staticResolver;
        this.debugAppender = debugAppender;
    }

    private static Map<String, App> loadApps(AppDiscoverer appDiscoverer, AppCreator appCreator) {
        return appDiscoverer.getAppReferences()
                .map(appReference -> {
                    App app = appCreator.createApp(appReference);
                    log.info("App '" + app.getName() + "' created.");
                    return app;
                })
                .collect(Collectors.toMap(App::getContext, app -> app));
    }

    public Response.ResponseBuilder serve(HttpRequest request) {
        if (log.isDebugEnabled() && !RequestUtil.isDebugRequest(request)) {
            log.debug("HTTP request received " + request);
        }

        try {
            if (!RequestUtil.isValid(request)) {
                throw new HttpErrorException(400, "Invalid URI '" + request.getUri() + "'.");
            }
            if (request.getUri().equals("/favicon.ico")) {
                // TODO: send default favicon.
            }

            App app = apps.get(request.getAppContext());
            if (app == null) {
                throw new HttpErrorException(404, "Cannot find an app for context '" + request.getAppContext() + "'.");
            }
            if (RequestUtil.isStaticResourceRequest(request)) {
                return staticResolver.createResponse(app, request);
            }
            if (RequestUtil.isDebugRequest(request)) {
                return renderDebug(app, request.getUriWithoutAppContext());
            }
            RequestLookup requestLookup = new RequestLookup(request);
            String html;
            if (RequestUtil.isFragmentRequest(request)) {
                html = app.renderFragment(request.getUriWithoutAppContext(), requestLookup);
            } else {
                try {
                    html = app.renderPage(request.getUriWithoutAppContext(), requestLookup);
                } catch (PageNotFoundException e) {
                    // See https://googlewebmastercentral.blogspot.com/2010/04/to-slash-or-not-to-slash.html
                    // if the tailing / is extra or a it is missing, send 301
                    String uri = request.getUri();
                    String fixedUri = uri.endsWith("/") ? uri.substring(0, uri.length() - 1) : uri + "/";
                    if (app.hasPage(fixedUri)) {
                        return Response.status(301).header(HttpHeaders.LOCATION, request.getHostName() + fixedUri);
                    }
                    throw e;
                }
            }
            Response.ResponseBuilder responseBuilder = ifExistsAddResponseHeaders(Response.ok(html),
                                                                                  requestLookup.getResponseHeaders());
            return responseBuilder.header(HttpHeaders.CONTENT_TYPE, "text/html");
        } catch (PageNotFoundException | FragmentNotFoundException e) {
            return createErrorResponse(e);
        } catch (PageRedirectException e) {
            return Response.status(e.getHttpStatusCode()).header("Location", e.getRedirectUrl());
        } catch (HttpErrorException e) {
            return createErrorResponse(e);
        } catch (UUFException e) {
            return createErrorResponse("A server occurred while serving for request '" + request.getUrl() + "'.", e);
        } catch (Exception e) {
            return createErrorResponse(
                    "An unexpected error occurred while serving for request '" + request.getUrl() + "'.", e);
        }
    }

    private Response.ResponseBuilder renderDebug(App app, String uriWithoutAppContext) {
        if (uriWithoutAppContext.equals("/debug/api/pages/")) {
            //TODO: fix issues when same page is in multiple components
            return Response.ok(app.getComponents().entrySet().stream()
                                       .flatMap(entry -> entry.getValue().getPages().stream())
                                       .collect(Collectors.toSet()));
        }
        if (uriWithoutAppContext.startsWith("/debug/api/fragments/")) {
            return Response.ok(app.getComponents().entrySet().stream()
                                       .flatMap(entry -> entry.getValue().getFragments().values().stream())
                                       .collect(Collectors.toSet()));
        }
        if (uriWithoutAppContext.startsWith("/debug/logs")) {
            if (debugAppender == null) {
                return Response.status(Response.Status.GONE);
            } else {
                return Response.ok(debugAppender.asJson(), "application/json");
            }
        }
        if (uriWithoutAppContext.startsWith("/debug/")) {
            if (uriWithoutAppContext.endsWith("/")) {
                uriWithoutAppContext = uriWithoutAppContext + "index.html";
            }
            InputStream resourceAsStream = this.getClass().getResourceAsStream("/apps" + uriWithoutAppContext);
            if (resourceAsStream == null) {
                return Response.status(Response.Status.NOT_FOUND);
            }
            try {
                String debugContent = IOUtils.toString(resourceAsStream, "UTF-8");
                return Response.ok(debugContent, getMime(uriWithoutAppContext));
            } catch (IOException e) {
                return Response.serverError().entity(e.getMessage());
            }
        }
        throw new UUFException("Unknown debug request");
    }

    private String getMime(String resourcePath) {
        int extensionIndex = resourcePath.lastIndexOf(".");
        String extension = (extensionIndex == -1) ? resourcePath : resourcePath.substring(extensionIndex + 1,
                                                                                          resourcePath.length());
        Optional<String> mime = MimeMapper.getMimeType(extension);
        return (mime.isPresent()) ? mime.get() : "text/html";
    }

    private Response.ResponseBuilder ifExistsAddResponseHeaders(Response.ResponseBuilder responseBuilder,
                                                                Map<String, String> headers) {
        headers.entrySet().stream().forEach(
                entry -> responseBuilder.header(entry.getKey(), entry.getValue()));
        return responseBuilder;
    }

    private Response.ResponseBuilder createErrorResponse(HttpErrorException e) {
        return Response.status(e.getHttpStatusCode())
                .entity(e.getMessage())
                .header(HttpHeaders.CONTENT_TYPE, "text/plain");
    }

    private Response.ResponseBuilder createErrorResponse(String message, Exception e) {
        log.error(message, e);
        return Response.serverError().entity(message);
    }
}