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

import com.sedmelluq.discord.lavaplayer.tools.http.HttpContextFilter;
import com.sedmelluq.discord.lavaplayer.tools.http.HttpContextRetryCounter;
import java.net.URI;
import java.util.Arrays;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.util.EntityUtils;

/**
 * The filter on the HTTP client {@link SoundCloudAacTrack}s play with, modelled on lavaplayer's
 * {@code SoundCloudHttpContextFilter} minus what breaks SoundCloud's signed AAC links (adding a client id to every
 * request off {@code sndcdn.com}):
 * <ul>
 *   <li>every request gets lavaplayer's SoundCloud user agent;</li>
 *   <li>a request whose query already carries {@code client_id} (only ever the api-v2 lookup) gets the current
 *       client id in its place. When SoundCloud answers it with 401 (it rotated its web client id), the filter asks
 *       for a refresh and sends the request once more with the id it has then; a second 401 is the answer.</li>
 * </ul>
 * A signed link (a query with {@code Signature=}, {@code Policy=} or {@code Key-Pair-Id=}) is never changed, even if
 * it carries a {@code client_id}, and nothing else in any link is touched.
 * <p>
 * The refresh can only help when lavaplayer is willing to fetch a new id: its SoundCloud source fetches one at most
 * once an hour, counted from the start of its last fetch. When SoundCloud rotates its id within an hour of that, the
 * refresh changes nothing and the lookup keeps failing with 401 until the hour is up (or the bot restarts); the bot
 * can't do better without its own way of finding the id.
 */
final class SoundCloudStreamFilter implements HttpContextFilter
{
    static final String USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/76.0.3809.100 Safari/537.36";
    private static final String CLIENT_ID = "client_id=";
    /** Query parameters only a signed link has. */
    private static final String[] SIGNED = {"Signature=", "Policy=", "Key-Pair-Id="};
    private static final HttpContextRetryCounter retryCounter = new HttpContextRetryCounter("sc-aac-id-retry");

    private final Supplier<String> clientId;
    private final Consumer<String> refreshClientId;

    /**
     * @param clientId        SoundCloud's current client id
     * @param refreshClientId has the client id refreshed; it is given the lookup without its {@code client_id}
     */
    SoundCloudStreamFilter(Supplier<String> clientId, Consumer<String> refreshClientId)
    {
        this.clientId = clientId;
        this.refreshClientId = refreshClientId;
    }

    @Override
    public void onContextOpen(HttpClientContext context)
    {
    }

    @Override
    public void onContextClose(HttpClientContext context)
    {
    }

    @Override
    public void onRequest(HttpClientContext context, HttpUriRequest request, boolean isRepetition)
    {
        request.setHeader("User-Agent", USER_AGENT);
        if(!isLookup(request.getURI()))
            return;
        retryCounter.handleUpdate(context, isRepetition);
        String current = clientId.get();
        if(current != null && !current.isEmpty() && request instanceof HttpRequestBase)
            ((HttpRequestBase) request).setURI(URI.create(
                    SoundCloudAacTrack.withClientId(withoutClientId(request.getURI().toString()), current)));
    }

    @Override
    public boolean onRequestResponse(HttpClientContext context, HttpUriRequest request, HttpResponse response)
    {
        if(response.getStatusLine().getStatusCode() != HttpStatus.SC_UNAUTHORIZED || !isLookup(request.getURI())
                || retryCounter.getRetryCount(context) >= 1)
            return false;
        // lavaplayer sends the request again without closing this answer: free its connection
        EntityUtils.consumeQuietly(response.getEntity());
        refreshClientId.accept(withoutClientId(request.getURI().toString()));
        return true;
    }

    @Override
    public boolean onRequestException(HttpClientContext context, HttpUriRequest request, Throwable error)
    {
        return false;
    }

    /** Whether the filter looks after the link's client id: its query carries one and nothing of a signed link. */
    private static boolean isLookup(URI link)
    {
        String query = link.getRawQuery();
        if(query == null)
            return false;
        boolean clientId = false;
        for(String parameter : query.split("&"))
        {
            for(String signed : SIGNED)
                if(parameter.startsWith(signed))
                    return false;
            clientId |= parameter.startsWith(CLIENT_ID);
        }
        return clientId;
    }

    /** {@code link} without its {@code client_id} parameters (and without the {@code ?} when nothing is left). */
    static String withoutClientId(String link)
    {
        int queryStart = link.indexOf('?');
        if(queryStart < 0)
            return link;
        String query = Arrays.stream(link.substring(queryStart + 1).split("&"))
                .filter(parameter -> !parameter.startsWith(CLIENT_ID))
                .collect(Collectors.joining("&"));
        return query.isEmpty() ? link.substring(0, queryStart) : link.substring(0, queryStart + 1) + query;
    }
}
