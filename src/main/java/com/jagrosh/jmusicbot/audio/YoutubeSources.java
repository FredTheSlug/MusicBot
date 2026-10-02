package com.jagrosh.jmusicbot.audio;

import com.jagrosh.jmusicbot.BotConfig;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterfaceManager;
import dev.lavalink.youtube.YoutubeAudioSourceManager;
import dev.lavalink.youtube.YoutubeSourceOptions;
import dev.lavalink.youtube.clients.AndroidMusic;
import dev.lavalink.youtube.clients.AndroidVr;
import dev.lavalink.youtube.clients.ClientOptions;
import dev.lavalink.youtube.clients.Ios;
import dev.lavalink.youtube.clients.MWeb;
import dev.lavalink.youtube.clients.Music;
import dev.lavalink.youtube.clients.Tv;
import dev.lavalink.youtube.clients.TvHtml5Simply;
import dev.lavalink.youtube.clients.Web;
import dev.lavalink.youtube.clients.WebEmbedded;
import dev.lavalink.youtube.clients.skeleton.Client;
import org.apache.http.HttpException;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.conn.routing.HttpRoutePlanner;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.conn.DefaultRoutePlanner;
import org.apache.http.impl.conn.DefaultSchemePortResolver;
import org.apache.http.protocol.HttpContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class YoutubeSources
{
    private static final Logger log = LoggerFactory.getLogger(YoutubeSources.class);

    private YoutubeSources() {}

    public static YoutubeAudioSourceManager create(BotConfig config)
    {
        YoutubeSourceOptions options = new YoutubeSourceOptions()
                .setAllowSearch(true)
                .setAllowDirectVideoIds(true)
                .setAllowDirectPlaylistIds(true);

        String cipherUrl = config.getYoutubeRemoteCipherUrl();
        if(cipherUrl != null && !cipherUrl.isBlank())
        {
            options.setRemoteCipher(
                    cipherUrl.trim(),
                    nullToEmpty(config.getYoutubeRemoteCipherPassword()),
                    nullToEmpty(config.getYoutubeRemoteCipherUserAgent()));
            log.info("YouTube remote cipher enabled ({})", cipherUrl.trim());
        }

        applyPoToken(config);

        boolean oauthEnabled = config.isYoutubeOauthEnabled();
        Client[] clients = oauthEnabled ? buildOauthDatacenterClients() : buildStandardClients();
        YoutubeAudioSourceManager yt = new YoutubeAudioSourceManager(options, clients);
        yt.setPlaylistPageCount(config.getMaxYTPlaylistPages());
        log.info("YouTube client profile: {}", oauthEnabled ? "oauth-datacenter" : "standard");
        log.info("YouTube clients: {}", describeClients(clients));

        // Proxy first (YouTube media via Bright Data). YouTube OAuth paths stay direct — see shouldBypassProxy.
        applyProxy(yt, config);
        applyOauth(yt, config, oauthEnabled);

        return yt;
    }

    private static void applyOauth(YoutubeAudioSourceManager yt, BotConfig config, boolean oauthEnabled)
    {
        if(!oauthEnabled)
        {
            log.warn("YouTube OAuth is disabled. On VPS/datacenter IPs playback often fails — set youtube.oauth.enabled = true.");
            return;
        }

        String refreshToken = config.getYoutubeOauthRefreshToken();
        if(refreshToken != null && !refreshToken.isBlank())
        {
            try
            {
                yt.useOauth2(refreshToken.trim(), true);
                log.info("YouTube OAuth enabled (refresh token from config)");
            }
            catch(Throwable ex)
            {
                log.error("YouTube OAuth token refresh failed — bot will continue without OAuth. "
                        + "Check refreshtoken is valid (starts with 1//). Token refresh uses www.youtube.com/o/oauth2/ directly (not via proxy).", ex);
            }
            return;
        }

        // Empty refreshtoken → device sign-in. OAuth paths bypass the proxy when enabled.
        try
        {
            yt.useOauth2(null, false);
            log.warn("YouTube OAuth: starting device sign-in now — watch for the Google URL and code below.");
            log.warn("Use a burner Google account. After sign-in, save the refresh token to youtube.oauth.refreshtoken in config.txt, set skipinitialization = true, and restart.");
        }
        catch(Throwable ex)
        {
            log.error("YouTube OAuth device sign-in failed to start. If proxy is enabled, set proxy.enabled = false, restart, complete sign-in, then re-enable proxy.", ex);
        }
    }

    /**
     * For VPS/datacenter IPs with OAuth: TV is the only OAuth-capable playback client and must be tried first.
     * WEB is metadata/search only (SABR responses have no direct stream URLs).
     */
    private static Client[] buildOauthDatacenterClients()
    {
        ClientOptions metadataOnly = metadataOnlyOptions();
        List<Client> clients = new ArrayList<>();
        clients.add(new Tv());
        clients.add(new AndroidVr());
        clients.add(new TvHtml5Simply());
        clients.add(new MWeb());
        clients.add(new WebEmbedded());
        clients.add(new Ios());
        clients.add(new Web(metadataOnly));
        clients.add(new AndroidMusic());
        clients.add(new Music());
        return clients.toArray(new Client[0]);
    }

    /** Home/residential IPs without OAuth. */
    private static Client[] buildStandardClients()
    {
        ClientOptions metadataOnly = metadataOnlyOptions();
        List<Client> clients = new ArrayList<>();
        clients.add(new MWeb());
        clients.add(new Ios());
        clients.add(new AndroidVr());
        clients.add(new TvHtml5Simply());
        clients.add(new WebEmbedded());
        clients.add(new Web(metadataOnly));
        clients.add(new AndroidMusic());
        clients.add(new Music());
        return clients.toArray(new Client[0]);
    }

    private static ClientOptions metadataOnlyOptions()
    {
        ClientOptions options = new ClientOptions();
        options.setPlayback(false);
        return options;
    }

    private static void applyProxy(YoutubeAudioSourceManager yt, BotConfig config)
    {
        if(!config.isYoutubeProxyEnabled())
            return;

        String host = config.getYoutubeProxyHost();
        int port = config.getYoutubeProxyPort();
        if(host == null || host.isBlank() || port <= 0)
        {
            log.warn("YouTube proxy enabled but host/port missing — ignoring proxy config.");
            return;
        }

        String type = nullToEmpty(config.getYoutubeProxyType()).trim().toLowerCase(Locale.ROOT);
        if(type.isEmpty())
            type = "http";
        if(!"http".equals(type) && !"https".equals(type))
        {
            log.warn("YouTube proxy type '{}' is not supported (use http). SOCKS is not wired yet — ask your provider for an HTTP(S) gateway.", type);
            return;
        }

        HttpHost proxy = new HttpHost(host.trim(), port, "http".equals(type) ? "http" : "https");
        HttpInterfaceManager http = yt.getHttpInterfaceManager();

        String username = config.getYoutubeProxyUsername();
        String password = config.getYoutubeProxyPassword();
        final CredentialsProvider credentials;
        if(username != null && !username.isBlank())
        {
            credentials = new BasicCredentialsProvider();
            credentials.setCredentials(
                    new AuthScope(host.trim(), port),
                    new UsernamePasswordCredentials(username.trim(), nullToEmpty(password)));
        }
        else
        {
            credentials = null;
        }

        // Stream URLs are IP-bound: Innertube player API + googlevideo must share an exit IP.
        // mode=full (default): proxy youtubei + googlevideo; keep embed/HTML/OAuth/cipher direct
        //   (Bright Data often 403s www.youtube.com/embed/ used for player-script discovery)
        // mode=cdn: only googlevideo — usually 403s streams when formats were fetched on the VPS
        final String mode = nullToEmpty(config.getYoutubeProxyMode()).trim().toLowerCase(Locale.ROOT);
        final boolean cdnOnly = "cdn".equals(mode);
        HttpRoutePlanner planner = new SelectiveProxyRoutePlanner(proxy, cdnOnly);
        http.configureBuilder(builder ->
        {
            builder.setRoutePlanner(planner);
            if(credentials != null)
                builder.setDefaultCredentialsProvider(credentials);
        });

        String modeLabel = cdnOnly ? "cdn" : "full (innertube+cdn; embed direct)";
        if(credentials != null)
            log.info("YouTube HTTP proxy enabled ({}:{}, auth=yes, mode={})", host.trim(), port, modeLabel);
        else
            log.info("YouTube HTTP proxy enabled ({}:{}, auth=no, mode={})", host.trim(), port, modeLabel);
    }

    private static boolean shouldUseProxy(String hostname, HttpRequest request, boolean cdnOnly)
    {
        if(hostname == null || hostname.isBlank())
            return false;
        String h = hostname.toLowerCase(Locale.ROOT);
        String uri = requestUri(request);

        // Always keep OAuth + remote cipher on the VPS.
        if(h.equals("cipher.kikkia.dev") || h.endsWith(".kikkia.dev"))
            return false;
        if(h.equals("accounts.google.com") || h.endsWith(".accounts.google.com"))
            return false;
        if(h.equals("oauth2.googleapis.com"))
            return false;

        // Audio/video CDN — must match Innertube exit IP.
        if(h.equals("googlevideo.com") || h.endsWith(".googlevideo.com")
                || h.equals("googleusercontent.com") || h.endsWith(".googleusercontent.com"))
            return true;

        if(cdnOnly)
            return false;

        // Innertube player/search API (IP that signs stream URLs).
        if(h.equals("youtubei.googleapis.com")
                || (h.endsWith(".googleapis.com") && h.contains("youtube")))
            return true;

        // Only /youtubei/ on youtube.com hosts — never HTML/embed/player JS (Bright Data 403s those).
        if(isYoutubeWebHost(h))
        {
            if(uri.contains("/o/oauth2/") || uri.contains("/embed") || uri.contains("/s/player/"))
                return false;
            return uri.contains("/youtubei/");
        }

        return false;
    }

    private static boolean isYoutubeWebHost(String h)
    {
        return h.equals("www.youtube.com") || h.equals("youtube.com") || h.equals("m.youtube.com")
                || h.equals("music.youtube.com") || h.endsWith(".youtube.com") || h.equals("youtu.be");
    }

    private static String requestUri(HttpRequest request)
    {
        if(request == null || request.getRequestLine() == null)
            return "";
        String uri = request.getRequestLine().getUri();
        return uri == null ? "" : uri;
    }

    private static final class SelectiveProxyRoutePlanner implements HttpRoutePlanner
    {
        private final HttpHost proxy;
        private final boolean cdnOnly;
        private final DefaultRoutePlanner direct = new DefaultRoutePlanner(DefaultSchemePortResolver.INSTANCE);

        private SelectiveProxyRoutePlanner(HttpHost proxy, boolean cdnOnly)
        {
            this.proxy = proxy;
            this.cdnOnly = cdnOnly;
        }

        @Override
        public HttpRoute determineRoute(HttpHost target, HttpRequest request, HttpContext context) throws HttpException
        {
            if(!shouldUseProxy(target.getHostName(), request, cdnOnly))
                return direct.determineRoute(target, request, context);

            boolean secure = "https".equalsIgnoreCase(target.getSchemeName());
            return new HttpRoute(target, null, proxy, secure);
        }
    }

    private static void applyPoToken(BotConfig config)
    {
        String poToken = config.getYoutubePoToken();
        String visitorData = config.getYoutubePoTokenVisitorData();
        if(poToken != null && !poToken.isBlank() && visitorData != null && !visitorData.isBlank())
        {
            Web.setPoTokenAndVisitorData(poToken.trim(), visitorData.trim());
            log.info("YouTube poToken configured (WEB/MWEB clients)");
        }
    }

    private static String describeClients(Client[] clients)
    {
        StringBuilder sb = new StringBuilder();
        for(int i = 0; i < clients.length; i++)
        {
            if(i > 0)
                sb.append(", ");
            sb.append(clients[i].getIdentifier());
        }
        return sb.toString();
    }

    private static String nullToEmpty(String value)
    {
        return value == null ? "" : value;
    }
}
