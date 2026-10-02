package com.jagrosh.jmusicbot;

import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import dev.lavalink.youtube.YoutubeAudioSourceManager;
import dev.lavalink.youtube.YoutubeSourceOptions;
import dev.lavalink.youtube.clients.ClientOptions;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

/**
 * Live network test — skipped in CI unless -DliveYoutube=true.
 */
public class YoutubeLoadTest
{
    @Test
    public void loadDirectYoutubeUrl() throws Exception
    {
        if (!"true".equals(System.getProperty("liveYoutube")))
            return;

        DefaultAudioPlayerManager manager = new DefaultAudioPlayerManager();
        manager.registerSourceManager(new YoutubeAudioSourceManager(true));

        AtomicReference<String> failure = new AtomicReference<>();
        AtomicReference<AudioTrack> track = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        manager.loadItem("https://www.youtube.com/watch?v=dQw4w9WgXcQ", new AudioLoadResultHandler()
        {
            @Override
            public void trackLoaded(AudioTrack loaded)
            {
                track.set(loaded);
                latch.countDown();
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) { latch.countDown(); }

            @Override
            public void noMatches()
            {
                failure.set("noMatches");
                latch.countDown();
            }

            @Override
            public void loadFailed(FriendlyException exception)
            {
                failure.set(exception.getMessage());
                exception.printStackTrace();
                latch.countDown();
            }
        });

        if (!latch.await(45, TimeUnit.SECONDS))
            fail("timed out loading youtube track");

        if (failure.get() != null)
            fail("load failed: " + failure.get());

        assertNotNull(track.get());
        System.out.println("Loaded: " + track.get().getInfo().title);
    }

    @Test
    public void loadYoutubeSearch() throws Exception
    {
        if (!"true".equals(System.getProperty("liveYoutube")))
            return;

        DefaultAudioPlayerManager manager = new DefaultAudioPlayerManager();
        manager.registerSourceManager(new YoutubeAudioSourceManager(true));

        AtomicReference<String> failure = new AtomicReference<>();
        AtomicReference<AudioTrack> track = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        manager.loadItem("ytsearch:never gonna give you up", new AudioLoadResultHandler()
        {
            @Override
            public void trackLoaded(AudioTrack loaded)
            {
                track.set(loaded);
                latch.countDown();
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist)
            {
                if (playlist.getSelectedTrack() != null)
                    track.set(playlist.getSelectedTrack());
                else if (!playlist.getTracks().isEmpty())
                    track.set(playlist.getTracks().get(0));
                latch.countDown();
            }

            @Override
            public void noMatches()
            {
                failure.set("noMatches");
                latch.countDown();
            }

            @Override
            public void loadFailed(FriendlyException exception)
            {
                failure.set(exception.getMessage());
                exception.printStackTrace();
                latch.countDown();
            }
        });

        if (!latch.await(45, TimeUnit.SECONDS))
            fail("timed out loading ytsearch");

        if (failure.get() != null)
            fail("load failed: " + failure.get());

        assertNotNull(track.get());
        System.out.println("Search loaded: " + track.get().getInfo().title);
    }

    @Test
    public void streamYoutubeAudioFrames() throws Exception
    {
        if (!"true".equals(System.getProperty("liveYoutube")))
            return;

        DefaultAudioPlayerManager manager = new DefaultAudioPlayerManager();
        manager.registerSourceManager(new YoutubeAudioSourceManager(true));
        var player = manager.createPlayer();

        AtomicReference<String> failure = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        manager.loadItem("https://www.youtube.com/watch?v=dQw4w9WgXcQ", new AudioLoadResultHandler()
        {
            @Override
            public void trackLoaded(AudioTrack loaded)
            {
                player.playTrack(loaded);
                latch.countDown();
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) { latch.countDown(); }

            @Override
            public void noMatches()
            {
                failure.set("noMatches");
                latch.countDown();
            }

            @Override
            public void loadFailed(FriendlyException exception)
            {
                failure.set(exception.getMessage());
                exception.printStackTrace();
                latch.countDown();
            }
        });

        if (!latch.await(45, TimeUnit.SECONDS))
            fail("timed out loading for stream test");

        if (failure.get() != null)
            fail("load failed: " + failure.get());

        int frames = 0;
        for (int i = 0; i < 200; i++)
        {
            var frame = player.provide();
            if (frame != null)
                frames++;
            Thread.sleep(5);
        }

        System.out.println("Produced " + frames + " audio frames");
        if (frames == 0)
            fail("track loaded but produced zero audio frames");
    }

    @Test
    public void streamYoutubeWithRemoteCipher() throws Exception
    {
        if (!"true".equals(System.getProperty("liveYoutube")))
            return;

        ClientOptions metadataOnly = new ClientOptions();
        metadataOnly.setPlayback(false);
        YoutubeSourceOptions options = new YoutubeSourceOptions()
                .setRemoteCipher("https://cipher.kikkia.dev/", "", "JMusicBot-Test");
        DefaultAudioPlayerManager manager = new DefaultAudioPlayerManager();
        manager.registerSourceManager(new YoutubeAudioSourceManager(options,
                new dev.lavalink.youtube.clients.MWeb(),
                new dev.lavalink.youtube.clients.Ios(),
                new dev.lavalink.youtube.clients.AndroidVr(),
                new dev.lavalink.youtube.clients.TvHtml5Simply(),
                new dev.lavalink.youtube.clients.WebEmbedded(),
                new dev.lavalink.youtube.clients.Web(metadataOnly),
                new dev.lavalink.youtube.clients.AndroidMusic(),
                new dev.lavalink.youtube.clients.Music()));
        var player = manager.createPlayer();

        AtomicReference<String> failure = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        manager.loadItem("https://www.youtube.com/watch?v=dQw4w9WgXcQ", new AudioLoadResultHandler()
        {
            @Override
            public void trackLoaded(AudioTrack loaded)
            {
                player.playTrack(loaded);
                latch.countDown();
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) { latch.countDown(); }

            @Override
            public void noMatches()
            {
                failure.set("noMatches");
                latch.countDown();
            }

            @Override
            public void loadFailed(FriendlyException exception)
            {
                failure.set(exception.getMessage());
                latch.countDown();
            }
        });

        if (!latch.await(45, TimeUnit.SECONDS))
            fail("timed out");

        if (failure.get() != null)
            fail("load failed: " + failure.get());

        int frames = 0;
        for (int i = 0; i < 200; i++)
        {
            if (player.provide() != null)
                frames++;
            Thread.sleep(5);
        }

        if (frames == 0)
            fail("remote cipher stream produced zero frames");
    }
}
