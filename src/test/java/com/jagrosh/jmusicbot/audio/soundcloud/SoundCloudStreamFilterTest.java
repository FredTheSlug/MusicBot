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
package com.jagrosh.jmusicbot.audio.soundcloud;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.http.HttpVersion;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.entity.InputStreamEntity;
import org.apache.http.message.BasicHttpResponse;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * The filter on its own, called the way lavaplayer's {@code HttpInterface} calls it: {@code onRequest} before every
 * try (the retry with {@code isRepetition}), {@code onRequestResponse} after it; true from the latter means "send it
 * again", without lavaplayer closing the answer.
 */
public class SoundCloudStreamFilterTest
{
    private static final String LOOKUP = "https://api-v2.soundcloud.com/media/soundcloud:tracks:1/a/stream/hls";
    private static final String SIGNED_CHUNK = "https://playback.media-streaming.soundcloud.cloud/a/aac_160k/data0.m4s"
            + "?expires=1&Policy=p&Signature=s&Key-Pair-Id=k";

    private final List<String> refreshes = new ArrayList<>();
    private final SoundCloudStreamFilter filter = new SoundCloudStreamFilter(
            () -> refreshes.isEmpty() ? "stale" : "fresh", refreshes::add);
    private final HttpClientContext context = HttpClientContext.create();

    @Test
    public void a401IsReadToTheEndThenTheLookupIsSentAgainWithTheNewId()
    {
        HttpGet lookup = new HttpGet(LOOKUP + "?client_id=stale");
        filter.onRequest(context, lookup, false);
        TrackedBody body = new TrackedBody("{\"error\":\"invalid client id\"}");

        assertTrue(filter.onRequestResponse(context, lookup, unauthorized(body)));

        // lavaplayer sends the request again without closing this answer: its connection is only freed by reading it
        assertTrue("the answer was not consumed", body.closed || body.readToTheEnd());
        assertEquals(Collections.singletonList(LOOKUP), refreshes);

        filter.onRequest(context, lookup, true);
        assertEquals(LOOKUP + "?client_id=fresh", lookup.getURI().toString());
        assertEquals(SoundCloudStreamFilter.USER_AGENT, lookup.getFirstHeader("User-Agent").getValue());

        // a second 401 is the answer: no second refresh, no third try
        assertFalse(filter.onRequestResponse(context, lookup, unauthorized(new TrackedBody("{}"))));
        assertEquals(1, refreshes.size());
    }

    @Test
    public void aSignedLinkIsNeverTouchedEvenWithAClientId()
    {
        for(String signed : new String[]{SIGNED_CHUNK + "&client_id=old", LOOKUP + "?client_id=old&Signature=s",
                LOOKUP + "?client_id=old&Policy=p", LOOKUP + "?Key-Pair-Id=k&client_id=old"})
        {
            HttpGet request = new HttpGet(signed);

            filter.onRequest(context, request, false);

            assertEquals(signed, request.getURI().toString());
            assertFalse(signed, filter.onRequestResponse(context, request, unauthorized(new TrackedBody("{}"))));
        }
        assertEquals(Collections.emptyList(), refreshes);
    }

    private static BasicHttpResponse unauthorized(TrackedBody body)
    {
        BasicHttpResponse response = new BasicHttpResponse(HttpVersion.HTTP_1_1, 401, "Unauthorized");
        response.setEntity(new InputStreamEntity(body));
        return response;
    }

    /** An answer's body that knows whether it was read to the end or closed. */
    private static final class TrackedBody extends ByteArrayInputStream
    {
        boolean closed;

        TrackedBody(String text)
        {
            super(text.getBytes(StandardCharsets.UTF_8));
        }

        boolean readToTheEnd()
        {
            return available() == 0;
        }

        @Override
        public void close() throws IOException
        {
            closed = true;
            super.close();
        }
    }
}
