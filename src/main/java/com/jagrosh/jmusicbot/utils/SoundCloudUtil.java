/*
 * Copyright 2026 Fred (https://github.com/FredTheSlug)
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
package com.jagrosh.jmusicbot.utils;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

public final class SoundCloudUtil
{
    private SoundCloudUtil() {}

    public static String normalizePlaybackUrl(String url)
    {
        if (url == null) return null;
        try
        {
            URI u = URI.create(url.trim());
            String host = u.getHost();
            if (host == null) return url;
            String h = host.toLowerCase(Locale.ROOT);
            if (h.startsWith("m.soundcloud.com") || h.startsWith("www.soundcloud.com"))
            {
                String newHost = "soundcloud.com";
                return new URI(u.getScheme(), u.getUserInfo(), newHost, u.getPort(), u.getPath(), u.getQuery(), u.getFragment()).toASCIIString();
            }
        }
        catch (URISyntaxException | IllegalArgumentException ignored) {}
        return url;
    }

    public static boolean isSoundCloudHttpUrl(String query)
    {
        if (query == null) return false;
        String s = query.trim().toLowerCase(Locale.ROOT);
        return (s.startsWith("http://") || s.startsWith("https://")) && s.contains("soundcloud.com");
    }
}
