/*
 * Copyright 2019 Sedmelluq and Lavaplayer contributors.
 *
 * Modified 2026 by Fred (https://github.com/FredTheSlug/MusicBot) for SoundCloud HTML/API drift.
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
 *
 * Upstream picked a single webpack URL (last among the first N matches). That breaks when SoundCloud
 * adds/removes chunks or when the client id moves. We scan multiple candidate bundles (newest first)
 * and fall back to the web client's api id embedded in {@code window.__sc_hydration}.
 *
 * Original class excluded from the shaded jar via pom.xml.
 */
package com.sedmelluq.discord.lavaplayer.source.soundcloud;

import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterfaceManager;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SoundCloudClientIdTracker {
    private static final Logger log = LoggerFactory.getLogger(SoundCloudClientIdTracker.class);

    private static final String ID_FETCH_CONTEXT_ATTRIBUTE = "sc-raw";
    private static final long CLIENT_ID_REFRESH_INTERVAL = TimeUnit.HOURS.toMillis(1);
    private static final String PAGE_APP_SCRIPT_REGEX = "https://[A-Za-z0-9-.]+/assets/[a-f0-9-]+\\.js";
    private static final String APP_SCRIPT_CLIENT_ID_REGEX = "[^_]client_id:\"([a-zA-Z0-9-_]+)\"";

    private static final int MAX_SCRIPT_URLS = 48;

    private static final Pattern pageAppScriptPattern = Pattern.compile(PAGE_APP_SCRIPT_REGEX);
    private static final Pattern appScriptClientIdPattern = Pattern.compile(APP_SCRIPT_CLIENT_ID_REGEX);

    private final Object clientIdLock = new Object();
    private final HttpInterfaceManager httpInterfaceManager;
    private String clientId;
    private long lastClientIdUpdate;

    public SoundCloudClientIdTracker(HttpInterfaceManager httpInterfaceManager) {
        this.httpInterfaceManager = httpInterfaceManager;
    }

    public void updateClientId() {
        synchronized (clientIdLock) {
            long now = System.currentTimeMillis();
            if (now - lastClientIdUpdate < CLIENT_ID_REFRESH_INTERVAL) {
                log.debug("Client ID was recently updated, not updating again right away.");
                return;
            }

            lastClientIdUpdate = now;
            log.info("Updating SoundCloud client ID (current is {}).", clientId);

            try {
                String found = findClientIdFromSite();
                if (found != null && !found.isEmpty()) {
                    clientId = found;
                    log.info("Updating SoundCloud client ID succeeded, new ID is {}.", clientId);
                } else {
                    log.error("SoundCloud client ID update produced empty id.");
                }
            } catch (Exception e) {
                log.error("SoundCloud client ID update failed.", e);
            }
        }
    }

    public String getClientId() {
        synchronized (clientIdLock) {
            if (clientId == null) {
                updateClientId();
            }

            return clientId;
        }
    }

    public boolean isIdFetchContext(HttpClientContext context) {
        return context.getAttribute(ID_FETCH_CONTEXT_ATTRIBUTE) == Boolean.TRUE;
    }

    private String findClientIdFromSite() throws IOException {
        try (HttpInterface httpInterface = httpInterfaceManager.getInterface()) {
            httpInterface.getContext().setAttribute(ID_FETCH_CONTEXT_ATTRIBUTE, true);

            String page = fetchSoundCloudHomeHtml(httpInterface);

            List<String> scriptUrls = collectScriptUrls(page);
            if (scriptUrls.isEmpty()) {
                throw new IllegalStateException("Could not find application script URLs on main page.");
            }

            for (int pass = 0; pass < 2; pass++) {
                boolean reverse = pass == 0;
                int n = scriptUrls.size();
                for (int i = reverse ? n - 1 : 0; reverse ? i >= 0 : i < n; i += reverse ? -1 : 1) {
                    String url = scriptUrls.get(i);
                    try {
                        String cid = readClientIdFromScript(httpInterface, url);
                        if (cid != null && !cid.isEmpty()) {
                            log.debug("SoundCloud client id from bundle {}.", url);
                            return cid;
                        }
                    } catch (IOException ex) {
                        log.debug("SoundCloud bundle {} skipped: {}", url, ex.getMessage());
                    }
                }
            }

            String hydration = tryHydrationApiClientId(page);
            if (hydration != null && !hydration.isEmpty()) {
                log.info("SoundCloud client id from __sc_hydration apiClient (script scan found no client_id).");
                return hydration;
            }

            throw new IllegalStateException("Could not find client ID in any SoundCloud script bundle.");
        }
    }

    private static String fetchSoundCloudHomeHtml(HttpInterface httpInterface) throws IOException {
        try (CloseableHttpResponse response = httpInterface.execute(new HttpGet("https://soundcloud.com"))) {
            int statusCode = response.getStatusLine().getStatusCode();
            if (!HttpClientTools.isSuccessWithContent(statusCode)) {
                throw new IOException("Invalid status code for main page response: " + statusCode);
            }
            return EntityUtils.toString(response.getEntity());
        }
    }

    /**
     * SoundCloud embeds {@code "hydratable":"apiClient","data":{"id":"..."}} in the home HTML; this id is accepted
     * as {@code client_id} for api-v2 calls in current web clients.
     */
    private static String tryHydrationApiClientId(String page) {
        String needle = "\"hydratable\":\"apiClient\"";
        int idx = page.indexOf(needle);
        if (idx < 0) {
            return null;
        }
        int end = Math.min(idx + 220, page.length());
        String snip = page.substring(idx, end);
        Matcher m = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"").matcher(snip);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    private static List<String> collectScriptUrls(String page) {
        List<String> urls = new ArrayList<>();
        Matcher scriptMatcher = pageAppScriptPattern.matcher(page);
        while (scriptMatcher.find() && urls.size() < MAX_SCRIPT_URLS) {
            urls.add(scriptMatcher.group());
        }
        return urls;
    }

    private static String readClientIdFromScript(HttpInterface httpInterface, String scriptUrl) throws IOException {
        try (CloseableHttpResponse response = httpInterface.execute(new HttpGet(scriptUrl))) {
            int statusCode = response.getStatusLine().getStatusCode();
            if (!HttpClientTools.isSuccessWithContent(statusCode)) {
                throw new IOException("Invalid status code for application script response: " + statusCode);
            }

            String js = EntityUtils.toString(response.getEntity());
            Matcher clientIdMatcher = appScriptClientIdPattern.matcher(js);

            if (clientIdMatcher.find()) {
                return clientIdMatcher.group(1);
            }
            return null;
        }
    }
}
