/*
 * Copyright 2026 Fred (https://github.com/FredTheSlug/MusicBot).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jagrosh.jmusicbot.web;

import io.javalin.Javalin;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * A before-handler that only sets status 401 does not stop Javalin from running the
 * endpoint, so unauthenticated requests must never reach the route handler.
 */
public class WebAuthMiddlewareTest
{
    private Javalin app;
    private final AtomicBoolean endpointRan = new AtomicBoolean(false);

    @Before
    public void startServer()
    {
        WebServer web = new WebServer(null);
        app = Javalin.create();
        app.before("/api/*", web::authMiddleware);
        app.get("/api/probe", ctx -> { endpointRan.set(true); ctx.result("secret playlist data"); });
        app.start("127.0.0.1", 0);
    }

    @After
    public void stopServer()
    {
        app.stop();
    }

    @Test
    public void unauthenticatedRequestNeverReachesEndpoint() throws Exception
    {
        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/api/probe")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, resp.statusCode());
        assertFalse("endpoint handler ran without a session", endpointRan.get());
        assertFalse(resp.body().contains("secret playlist data"));
    }

    @Test
    public void unknownSessionCookieIsRejected() throws Exception
    {
        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/api/probe"))
                        .header("Cookie", "JMBSESSION=not-a-real-session").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, resp.statusCode());
        assertFalse(endpointRan.get());
    }
}
