/*
 * Copyright 2018 John Grosh <john.a.grosh@gmail.com>.
 *
 * Modified 2026 by Fred (https://github.com/FredTheSlug/MusicBot).
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
package com.jagrosh.jmusicbot;

import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import com.jagrosh.jdautilities.commons.waiter.EventWaiter;
import com.jagrosh.jmusicbot.audio.AloneInVoiceHandler;
import com.jagrosh.jmusicbot.audio.AudioHandler;
import com.jagrosh.jmusicbot.audio.NowplayingHandler;
import com.jagrosh.jmusicbot.audio.PlayerManager;
import com.jagrosh.jmusicbot.audio.SpotifyResolver;
import com.jagrosh.jmusicbot.gui.GUI;
import com.jagrosh.jmusicbot.playlist.LavaTrackResolver;
import com.jagrosh.jmusicbot.playlist.LikesStore;
import com.jagrosh.jmusicbot.playlist.PlaylistConverter;
import com.jagrosh.jmusicbot.playlist.PlaylistLoader;
import com.jagrosh.jmusicbot.playlist.PlaylistPlayer;
import com.jagrosh.jmusicbot.playlist.PlaylistStore;
import com.jagrosh.jmusicbot.playlist.TrackResolver;
import com.jagrosh.jmusicbot.settings.PlayCountStore;
import com.jagrosh.jmusicbot.settings.SettingsManager;
import com.jagrosh.jmusicbot.utils.OtherUtil;
import com.jagrosh.jmusicbot.web.WebServer;
import java.util.Objects;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Guild;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author John Grosh <john.a.grosh@gmail.com>
 */
public class Bot
{
    private static final Logger LOG = LoggerFactory.getLogger(Bot.class);

    private final EventWaiter waiter;
    private final ScheduledExecutorService threadpool;
    private final BotConfig config;
    private final SettingsManager settings;
    private final PlayCountStore playCounts;
    private final PlayerManager players;
    private final PlaylistLoader playlists;
    private final SpotifyResolver spotifyResolver;
    private final PlaylistStore playlistStore;
    private final LikesStore likesStore;
    private final TrackResolver trackResolver;
    private final PlaylistConverter playlistConverter;
    private final PlaylistPlayer playlistPlayer;
    private final NowplayingHandler nowplaying;
    private final AloneInVoiceHandler aloneInVoiceHandler;
    private final WebServer webServer;
    private final ScheduledExecutorService playlistUpkeep;

    private boolean shuttingDown = false;
    private JDA jda;
    private GUI gui;

    public Bot(EventWaiter waiter, BotConfig config, SettingsManager settings)
    {
        this.waiter = waiter;
        this.config = config;
        this.settings = settings;
        this.playCounts = new PlayCountStore();
        this.playlists = new PlaylistLoader(config);
        this.threadpool = Executors.newSingleThreadScheduledExecutor();
        this.players = new PlayerManager(this);
        this.players.init();
        this.spotifyResolver = SpotifyResolver.create(config);
        this.playlistStore = new PlaylistStore(OtherUtil.getPath(config.getPlaylistsFolder()), Clock.systemUTC());
        // the one LikesStore: its locks are per instance
        this.likesStore = new LikesStore(OtherUtil.getPath(config.getPlaylistsFolder()).resolve(".liked"), Clock.systemUTC());
        this.trackResolver = new LavaTrackResolver(players, spotifyResolver);
        this.playlistConverter = new PlaylistConverter(playlistStore, trackResolver);
        this.playlistPlayer = new PlaylistPlayer(players, playlistStore, playlists, config);
        this.nowplaying = new NowplayingHandler(this);
        this.nowplaying.init();
        this.aloneInVoiceHandler = new AloneInVoiceHandler(this);
        this.aloneInVoiceHandler.init();
        this.webServer = new WebServer(this);
        this.playlistUpkeep = startPlaylistUpkeep();
    }

    /**
     * Purges the Deleted folder and converts the old {@code .txt} playlists right away, then both again every
     * hour (a conversion that stopped for an outage picks up where it left off). On a thread of its own: a
     * conversion can take minutes and must not hold up the shared {@link #getThreadpool() threadpool}.
     */
    private ScheduledExecutorService startPlaylistUpkeep()
    {
        ScheduledExecutorService upkeep = Executors.newSingleThreadScheduledExecutor(task ->
        {
            Thread thread = new Thread(task, "playlist-upkeep");
            thread.setDaemon(true);
            return thread;
        });
        upkeep.scheduleWithFixedDelay(this::runPlaylistUpkeep, 0, 1, TimeUnit.HOURS);
        return upkeep;
    }

    /** One round of upkeep. Nothing escapes: an exception that did would end the schedule for good. */
    private void runPlaylistUpkeep()
    {
        try
        {
            int purged = playlistStore.purgeExpired();
            if(purged > 0)
                LOG.info("Purged {} expired playlist(s) from the Deleted folder", purged);
        }
        catch(Exception e)
        {
            LOG.warn("Purging the Deleted folder failed; it will be tried again in an hour", e);
        }
        try
        {
            int converted = playlistConverter.convertPending();
            if(converted > 0)
                LOG.info("Converted {} old playlist(s)", converted);
        }
        catch(Exception e)
        {
            LOG.warn("Converting old playlists failed; it will be tried again in an hour", e);
        }
    }

    public BotConfig getConfig()
    {
        return config;
    }
    
    public SettingsManager getSettingsManager()
    {
        return settings;
    }

    public PlayCountStore getPlayCountStore()
    {
        return playCounts;
    }
    
    public EventWaiter getWaiter()
    {
        return waiter;
    }
    
    public ScheduledExecutorService getThreadpool()
    {
        return threadpool;
    }
    
    public PlayerManager getPlayerManager()
    {
        return players;
    }
    
    public PlaylistLoader getPlaylistLoader()
    {
        return playlists;
    }

    public PlaylistStore getPlaylistStore()
    {
        return playlistStore;
    }

    /** Everyone's liked songs, in the hidden {@code .liked} folder of the playlists folder. */
    public LikesStore getLikesStore()
    {
        return likesStore;
    }

    public TrackResolver getTrackResolver()
    {
        return trackResolver;
    }

    public PlaylistConverter getPlaylistConverter()
    {
        return playlistConverter;
    }

    public PlaylistPlayer getPlaylistPlayer()
    {
        return playlistPlayer;
    }

    public NowplayingHandler getNowplayingHandler()
    {
        return nowplaying;
    }

    public AloneInVoiceHandler getAloneInVoiceHandler()
    {
        return aloneInVoiceHandler;
    }
    
    public WebServer getWebServer()
    {
        return webServer;
    }

    public SpotifyResolver getSpotifyResolver()
    {
        return spotifyResolver;
    }
    
    public JDA getJDA()
    {
        return jda;
    }
    
    public void closeAudioConnection(long guildId)
    {
        Guild guild = jda.getGuildById(guildId);
        if(guild!=null)
            threadpool.submit(() -> guild.getAudioManager().closeAudioConnection());
    }
    
    public void resetGame()
    {
        Activity game = config.getGame()==null || config.getGame().getName().equalsIgnoreCase("none") ? null : config.getGame();
        if(!Objects.equals(jda.getPresence().getActivity(), game))
            jda.getPresence().setActivity(game);
        OnlineStatus status = config.getStatus();
        if(status != OnlineStatus.UNKNOWN && jda.getPresence().getStatus() != status)
            jda.getPresence().setStatus(status);
    }

    public void shutdown()
    {
        if(shuttingDown)
            return;
        shuttingDown = true;
        threadpool.shutdownNow();
        playlistUpkeep.shutdownNow();
        if(config.isWebEnabled())
            webServer.stop();
        if(jda!=null && jda.getStatus()!=JDA.Status.SHUTTING_DOWN)
        {
            jda.getGuilds().stream().forEach(g -> 
            {
                g.getAudioManager().closeAudioConnection();
                AudioHandler ah = (AudioHandler)g.getAudioManager().getSendingHandler();
                if(ah!=null)
                {
                    ah.stopAndClear();
                    ah.getPlayer().destroy();
                }
            });
            jda.shutdown();
        }
        if(gui!=null)
            gui.dispose();
        System.exit(0);
    }

    public void setJDA(JDA jda)
    {
        this.jda = jda;
    }
    
    public void setGUI(GUI gui)
    {
        this.gui = gui;
    }
}
