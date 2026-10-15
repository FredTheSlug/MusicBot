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
import io.javalin.http.UploadedFile;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class UploadLimitTest
{
    private static final long LIMIT = 1024 * 1024;

    private final HttpClient http = HttpClient.newHttpClient();
    private final AtomicBoolean bodyRead = new AtomicBoolean();
    private Javalin app;

    @Before
    public void startServer()
    {
        app = Javalin.create();
        // the shape of an upload route: check the size first, then read the file
        app.post("/up", ctx ->
        {
            if(UploadLimit.tooLarge(ctx, LIMIT))
            {
                ctx.status(413).result("too large");
                return;
            }
            UploadedFile file;
            try
            {
                file = ctx.uploadedFile("file");
            }
            catch(IllegalStateException overTheLimit)
            {
                ctx.status(413).result("too large");
                return;
            }
            bodyRead.set(true);
            ctx.result("read " + (file == null ? -1 : file.size()));
        });
        WebServer web = new WebServer(null);
        app.post("/api/guild/{guildId}/upload", web::handleUpload);
        app.start("127.0.0.1", 0);
    }

    @After
    public void stopServer()
    {
        app.stop();
    }

    @Test
    public void anUploadThatAnnouncesTooMuchIsRefusedUnread() throws Exception
    {
        HttpResponse<String> answer = upload("/up", 5 * LIMIT, true);
        assertEquals(413, answer.statusCode());
        assertFalse("the route never read the upload", bodyRead.get());
    }

    @Test
    public void anUploadWithNoLengthIsStoppedAtTheLimit() throws Exception
    {
        // no length to look at: the server must stop reading by itself
        HttpResponse<String> answer = upload("/up", 5 * LIMIT, false);
        assertEquals(413, answer.statusCode());
        assertFalse(bodyRead.get());
    }

    @Test
    public void anUploadWithinTheLimitIsReadAsBefore() throws Exception
    {
        HttpResponse<String> answer = upload("/up", 100 * 1024, true);
        assertEquals(200, answer.statusCode());
        assertEquals("read " + (100 * 1024), answer.body());
    }

    @Test
    public void theAudioUploadRefusesAFileOverItsCapBeforeDoingAnythingElse() throws Exception
    {
        // the web server has no bot here: getting a 413 (not a crash) shows nothing else ran first
        HttpResponse<String> answer = upload("/api/guild/1/upload", WebServer.MAX_AUDIO_UPLOAD_BYTES + 1, true);
        assertEquals(413, answer.statusCode());
        assertEquals(WebServer.AUDIO_TOO_LARGE, answer.body());
    }

    /** Uploads a file of this many bytes, made up as it is sent, with its length announced or sent chunked. */
    private HttpResponse<String> upload(String path, long fileBytes, boolean announceLength) throws IOException, InterruptedException
    {
        String boundary = "----limittest" + UUID.randomUUID().toString().replace("-", "");
        byte[] head = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"song.mp3\"\r\n"
                + "Content-Type: audio/mpeg\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        Supplier<InputStream> body = () -> new SequenceInputStream(Collections.enumeration(Arrays.asList(
                new ByteArrayInputStream(head), new Zeros(fileBytes), new ByteArrayInputStream(tail))));
        HttpRequest.BodyPublisher publisher = announceLength
                ? HttpRequest.BodyPublishers.fromPublisher(HttpRequest.BodyPublishers.ofInputStream(body), head.length + fileBytes + tail.length)
                : HttpRequest.BodyPublishers.ofInputStream(body);
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + path))
                .POST(publisher).header("Content-Type", "multipart/form-data; boundary=" + boundary).build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** This many zero bytes, made up as they are read. */
    private static final class Zeros extends InputStream
    {
        private long left;

        Zeros(long size)
        {
            this.left = size;
        }

        @Override
        public int read()
        {
            if(left <= 0)
                return -1;
            left--;
            return 0;
        }

        @Override
        public int read(byte[] buffer, int offset, int length)
        {
            if(left <= 0)
                return -1;
            int count = (int) Math.min(length, left);
            Arrays.fill(buffer, offset, offset + count, (byte) 0);
            left -= count;
            return count;
        }
    }
}
