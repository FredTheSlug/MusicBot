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
