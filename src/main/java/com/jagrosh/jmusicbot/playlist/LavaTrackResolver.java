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
package com.jagrosh.jmusicbot.playlist;

import com.jagrosh.jmusicbot.audio.Autoplay;
import com.jagrosh.jmusicbot.audio.PlayerManager;
import com.jagrosh.jmusicbot.audio.SpotifyResolveResult;
import com.jagrosh.jmusicbot.audio.SpotifyResolver;
import com.jagrosh.jmusicbot.playlist.PlaylistFile.Song;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * The real {@link TrackResolver}: loads a line through lavaplayer, one load at a time, waiting at most
 * 30 seconds for each. A link gives what it points to (a single track, or every track of an album or
 * playlist, up to {@link PlaylistFile#MAX_SONGS}; a video link that also names a list gives just that
 * video), any other text is searched on YouTube and gives its first result. A Spotify link is expanded
 * by the {@link SpotifyResolver} into search queries, each loaded as the first YouTube result; a query
 * that finds nothing becomes an {@code unresolved} song. Only {@link #identifierFor}, {@link #songOf} and
 * {@link #fromPlaylist} are unit tested; the loading itself is checked by hand.
 */
public class LavaTrackResolver implements TrackResolver
{
    private static final long LOAD_SECONDS = 30;
    private static final long SPOTIFY_SECONDS = 60;
    /** The most songs of a Spotify link reported to a {@link Batches} at once. */
    private static final int BATCH_SIZE = 10;
    private static final String[] SEARCH_PREFIXES = {"ytsearch:", "ytmsearch:", "scsearch:"};
    private static final String SPOTIFY_OFF = "Spotify isn't set up on this bot";

    private final PlayerManager manager;
    private final SpotifyResolver spotify;

    /** {@code spotify} is null when the bot has no Spotify account set up. */
    public LavaTrackResolver(PlayerManager manager, SpotifyResolver spotify)
    {
        this.manager = manager;
        this.spotify = spotify;
    }

    @Override
    public Resolved resolve(String lineOrLink)
    {
        return resolve(lineOrLink, (songs, total) -> { });
    }

    /**
     * A Spotify link reports its songs in batches of at most 10 as they load. Anything else is one load,
     * reported as one batch; a line that gave no songs reports no batch.
     */
    @Override
    public Resolved resolve(String lineOrLink, Batches onBatch)
    {
        if(lineOrLink == null || lineOrLink.trim().isEmpty())
            return Resolved.failed("Nothing to load");
        String identifier = identifierFor(lineOrLink);
        if(isSpotify(identifier))
            return resolveSpotify(identifier, onBatch);
        Resolved resolved = load(identifier);
        if(!resolved.getSongs().isEmpty())
            onBatch.batch(resolved.getSongs(), resolved.getSongs().size());
        return resolved;
    }

    /**
     * What lavaplayer is asked to load for a line: links (and {@code spotify:} URIs) and lines that already
     * start with a search prefix as they are, anything else as a YouTube search.
     */
    static String identifierFor(String line)
    {
        String trimmed = line.trim();
        if(startsWithIgnoreCase(trimmed, "http://") || startsWithIgnoreCase(trimmed, "https://")
                || startsWithIgnoreCase(trimmed, "spotify:") || hasSearchPrefix(trimmed))
            return trimmed;
        return "ytsearch:" + trimmed;
    }

    /** The playlist entry for a loaded track. The id and who added it are the playlist's to fill in. */
    static Song songOf(AudioTrackInfo info)
    {
        Song song = new Song();
        song.setUri(info.uri);
        song.setTitle(info.title);
        song.setAuthor(info.author);
        // lavaplayer reports a stream's (or an unknown) length as Long.MAX_VALUE
        song.setDurationMs(info.isStream || info.length < 0 || info.length == Long.MAX_VALUE ? 0 : info.length);
        song.setArtworkUrl(artworkOf(info));
        return song;
    }

    /** What an album, playlist or search gave: see the class comment. */
    static Resolved fromPlaylist(AudioPlaylist playlist)
    {
        List<AudioTrack> tracks = playlist.getTracks();
        boolean search = playlist.isSearchResult();
        if(tracks == null || tracks.isEmpty())
            return Resolved.failed(search ? "No matches found" : "The playlist is empty");
        if(search)
            return single(tracks.get(0));
        if(playlist.getSelectedTrack() != null)
            return single(playlist.getSelectedTrack());
        List<Song> songs = new ArrayList<>();
        for(int i = 0; i < tracks.size() && songs.size() < PlaylistFile.MAX_SONGS; i++)
            songs.add(songOf(tracks.get(i).getInfo()));
        return Resolved.ok(songs, playlist.getName());
    }

    private static Resolved single(AudioTrack track)
    {
        return Resolved.ok(Collections.singletonList(songOf(track.getInfo())), null);
    }

    /** The track's artwork; for a YouTube video without any, its standard thumbnail. */
    private static String artworkOf(AudioTrackInfo info)
    {
        if(info.artworkUrl != null && !info.artworkUrl.trim().isEmpty())
            return info.artworkUrl;
        if(Autoplay.isYoutubeId(info.identifier) && info.uri != null && info.uri.contains("youtu"))
            return "https://i.ytimg.com/vi/" + info.identifier + "/hqdefault.jpg";
        return null;
    }

    private static boolean hasSearchPrefix(String text)
    {
        for(String prefix : SEARCH_PREFIXES)
            if(text.startsWith(prefix))
                return true;
        return false;
    }

    private static boolean startsWithIgnoreCase(String text, String prefix)
    {
        return text.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    /** A Spotify link, not a search that happens to mention one. */
    private static boolean isSpotify(String identifier)
    {
        return !hasSearchPrefix(identifier) && SpotifyResolver.looksLikeSpotifyUrl(identifier);
    }

    private Resolved resolveSpotify(String link, Batches onBatch)
    {
        if(spotify == null)
            return Resolved.failed(SPOTIFY_OFF);
        SpotifyResolveResult expanded;
        try
        {
            expanded = spotify.resolve(link).get(SPOTIFY_SECONDS, TimeUnit.SECONDS);
        }
        catch(TimeoutException e)
        {
            return Resolved.failed("Spotify took too long to answer");
        }
        catch(ExecutionException e)
        {
            return Resolved.failed("Spotify lookup failed: " + e.getCause());
        }
        catch(InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while loading " + link, e);
        }
        if(!expanded.isSuccess())
            return Resolved.failed(expanded.getError() != null ? expanded.getError() : "Nothing to load in that Spotify link");

        List<String> queries = expanded.searchQueries();
        int total = Math.min(queries.size(), PlaylistFile.MAX_SONGS);
        List<Song> songs = new ArrayList<>(total);
        List<Song> batch = new ArrayList<>(BATCH_SIZE);
        for(String query : queries.subList(0, total))
        {
            Resolved found = load("ytsearch:" + query);
            Song song = found.getSongs().isEmpty()
                    ? TrackResolver.unresolvedSong(query, "ytsearch:" + query)
                    : found.getSongs().get(0);
            songs.add(song);
            batch.add(song);
            if(batch.size() == BATCH_SIZE)
            {
                onBatch.batch(new ArrayList<>(batch), total);
                batch.clear();
            }
        }
        if(!batch.isEmpty())
            onBatch.batch(new ArrayList<>(batch), total);
        return Resolved.ok(songs, expanded.getCollectionTitle());
    }

    /** One lavaplayer load, waiting up to 30 seconds. Failures of the load itself come back as an error answer. */
    private Resolved load(String identifier)
    {
        CompletableFuture<Resolved> answer = new CompletableFuture<>();
        Future<Void> task = manager.loadItemOrdered(this, identifier, new AudioLoadResultHandler()
        {
            @Override
            public void trackLoaded(AudioTrack track)
            {
                complete(answer, () -> single(track));
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist)
            {
                complete(answer, () -> fromPlaylist(playlist));
            }

            @Override
            public void noMatches()
            {
                answer.complete(Resolved.failed("No matches found"));
            }

            @Override
            public void loadFailed(FriendlyException exception)
            {
                String reason = exception.getMessage();
                answer.complete(Resolved.failed(reason == null || reason.isEmpty() ? "Failed to load" : reason));
            }
        });
        try
        {
            return answer.get(LOAD_SECONDS, TimeUnit.SECONDS);
        }
        catch(TimeoutException e)
        {
            task.cancel(true); // so the next load doesn't queue up behind this one
            return Resolved.failed("Timed out after " + LOAD_SECONDS + " seconds");
        }
        catch(ExecutionException e)
        {
            return Resolved.failed("Failed to load: " + e.getCause());
        }
        catch(InterruptedException e)
        {
            task.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while loading " + identifier, e);
        }
    }

    /** Completes the answer from lavaplayer's thread; a result that can't be read must not leave the caller waiting. */
    private static void complete(CompletableFuture<Resolved> answer, Supplier<Resolved> result)
    {
        try
        {
            answer.complete(result.get());
        }
        catch(RuntimeException e)
        {
            answer.complete(Resolved.failed("Could not read what was loaded: " + e));
        }
    }
}
