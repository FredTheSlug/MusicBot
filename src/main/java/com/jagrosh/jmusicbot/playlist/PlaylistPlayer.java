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

import com.jagrosh.jmusicbot.BotConfig;
import com.jagrosh.jmusicbot.audio.PlayerManager;
import com.jagrosh.jmusicbot.playlist.PlaylistFile.Song;
import com.jagrosh.jmusicbot.playlist.PlaylistLoader.Playlist;
import com.jagrosh.jmusicbot.playlist.PlaylistLoader.PlaylistLoadError;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.dv8tion.jda.api.entities.Guild;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads a playlist's songs through lavaplayer, in the order {@link #order} chooses, for whoever plays
 * them: Discord's {@code play playlist}, the default playlist and the web panel. Every song is loaded
 * by its stored {@code uri} as it is (no search is added; a stored {@code ytsearch:} takes the first
 * result) and handed to the caller one track at a time, in order, so the caller decides what a track
 * does (queue it, play it right away). An unconverted {@code .txt} playlist plays exactly as it always
 * did, through {@link PlaylistLoader}. Only {@link #order}, {@link #tracksOf} and the collecting of
 * answers ({@link LoadRun}) are unit tested; the loading itself is checked by hand.
 */
public class PlaylistPlayer
{
    private static final Logger LOG = LoggerFactory.getLogger(PlaylistPlayer.class);

    /** Every song, in the playlist's order (shuffled when the playlist's own shuffle flag is on). */
    public static final String ALL = "all";
    /** Every song, shuffled. */
    public static final String SHUFFLE = "shuffle";
    /** One song first, then the ones after it, then the ones before it. */
    public static final String FROM = "from";

    private static final String NOT_FOUND = "Playlist not found";
    private static final String SONG_NOT_FOUND = "Song not found";
    private static final String NO_MATCHES = "No matches found.";
    private static final String TOO_LONG = "This track is longer than the allowed maximum";

    private final PlayerManager manager;
    private final PlaylistStore store;
    private final PlaylistLoader legacyLoader;
    private final BotConfig config;

    public PlaylistPlayer(PlayerManager manager, PlaylistStore store, PlaylistLoader legacyLoader, BotConfig config)
    {
        this.manager = manager;
        this.store = store;
        this.legacyLoader = legacyLoader;
        this.config = config;
    }

    /**
     * Loads the songs of a playlist and gives each track that loaded to {@code each}, in playlist order; then
     * calls {@code done} once with what happened. Both run on lavaplayer's loader threads, except when there is
     * nothing to load: then {@code done} runs at once, in this call. Tracks longer than the configured maximum
     * are not given out but reported as errors. Everything is appended: what a track does is up to {@code each}.
     * {@code mode} is {@link #ALL}, {@link #SHUFFLE} or {@link #FROM} (with the id of the song to start at); see
     * {@link #order}. An unconverted {@code .txt} playlist ignores {@code mode} and {@code songId} and plays all
     * its lines as it always did.
     *
     * @throws NoSuchElementException   {@code Playlist not found}, or {@code Song not found} for an unknown {@code songId}
     * @throws IllegalArgumentException for an unknown mode, or a name or folder the store does not allow
     */
    public void load(Guild guild, String name, String folder, String mode, String songId,
            Consumer<AudioTrack> each, Consumer<Result> done)
    {
        PlaylistFile file = store.get(name, folder).orElseThrow(() -> new NoSuchElementException(NOT_FOUND));
        if(file.isLegacy())
        {
            loadLegacy(name, folder, each, done);
            return;
        }
        List<Song> songs = order(file, mode, songId, ThreadLocalRandom.current());
        if(songs.isEmpty())
        {
            done.accept(new Result(0, Collections.<LoadError>emptyList(), 0));
            return;
        }
        LoadRun run = new LoadRun(songs, config::isTooLong, each, done);
        // One key for this playlist in this guild: its songs load one after the other, in order, while
        // other loads in the guild (a play command, a search) don't have to wait for the whole playlist.
        String key = "playlist:" + guild.getId() + ":" + (folder == null ? "" : folder) + "/" + name;
        for(int i = 0; i < songs.size(); i++)
            manager.loadItemOrdered(key, songs.get(i).getUri(), run.handler(i));
    }

    /** The old way: every raw line, through {@link PlaylistLoader}; a search gives its first track, a playlist link all of its tracks. */
    private void loadLegacy(String name, String folder, Consumer<AudioTrack> each, Consumer<Result> done)
    {
        Playlist legacy = legacyLoader.getPlaylistInFolder(name, folder == null ? "" : folder);
        if(legacy == null)
            throw new NoSuchElementException(NOT_FOUND);
        if(legacy.getItems().isEmpty()) // loadTracks would never call back
        {
            done.accept(new Result(0, Collections.<LoadError>emptyList(), 0));
            return;
        }
        legacy.loadTracks(manager, each, () ->
        {
            List<LoadError> errors = new ArrayList<>();
            for(PlaylistLoadError error : legacy.getErrors())
                errors.add(new LoadError(error.getIndex(), error.getItem(), error.getReason()));
            done.accept(new Result(legacy.getTracks().size(), errors, legacy.getItems().size()));
        });
    }

    /**
     * The songs of the playlist in the order they are queued; a new list, the playlist is left alone. Songs
     * that can't be played (unresolved, or without a uri) are left out. {@link #ALL}: the playlist's order,
     * shuffled with {@code rnd} when the playlist's shuffle flag is on. {@link #SHUFFLE}: shuffled with
     * {@code rnd}. {@link #FROM}: the song with id {@code songId}, then the songs after it, then the songs
     * before it; with the shuffle flag on, the song stays first and the rest are shuffled. A {@code from} song
     * that can't be played itself starts the order at its place in the playlist.
     *
     * @throws NoSuchElementException   {@code Song not found}, for {@link #FROM} and an id the playlist doesn't have
     * @throws IllegalArgumentException for any other mode
     */
    public static List<Song> order(PlaylistFile pl, String mode, String songId, Random rnd)
    {
        if(ALL.equals(mode) || SHUFFLE.equals(mode))
        {
            List<Song> songs = playable(pl.getSongs());
            if(SHUFFLE.equals(mode) || pl.isShuffle())
                Collections.shuffle(songs, rnd);
            return songs;
        }
        if(FROM.equals(mode))
            return orderFrom(pl, songId, rnd);
        throw new IllegalArgumentException("Unknown play mode: " + mode);
    }

    private static List<Song> orderFrom(PlaylistFile pl, String songId, Random rnd)
    {
        List<Song> all = pl.getSongs();
        int at = indexOf(all, songId);
        List<Song> songs = playable(all.subList(at, all.size()));
        songs.addAll(playable(all.subList(0, at)));
        if(pl.isShuffle())
        {
            int held = isPlayable(all.get(at)) ? 1 : 0; // the song asked for stays first
            Collections.shuffle(songs.subList(held, songs.size()), rnd);
        }
        return songs;
    }

    private static int indexOf(List<Song> songs, String songId)
    {
        for(int i = 0; i < songs.size(); i++)
            if(songs.get(i).getId().equals(songId))
                return i;
        throw new NoSuchElementException(SONG_NOT_FOUND);
    }

    private static List<Song> playable(List<Song> songs)
    {
        List<Song> playable = new ArrayList<>();
        for(Song song : songs)
            if(isPlayable(song))
                playable.add(song);
        return playable;
    }

    private static boolean isPlayable(Song song)
    {
        return !song.isUnresolved() && song.getUri() != null && !song.getUri().trim().isEmpty();
    }

    /**
     * True when playing the playlist would try at least one song. An unconverted {@code .txt} counts by its
     * lines (its view marks them all unresolved, but they all play).
     */
    public static boolean hasPlayable(PlaylistFile pl)
    {
        if(pl.isLegacy())
            return !pl.getSongs().isEmpty();
        for(Song song : pl.getSongs())
            if(isPlayable(song))
                return true;
        return false;
    }

    /**
     * The tracks an answer to one song's load stands for: the first result of a search, the selected track of
     * a playlist that has one, otherwise every track.
     */
    static List<AudioTrack> tracksOf(AudioPlaylist playlist)
    {
        List<AudioTrack> tracks = playlist.getTracks();
        if(tracks == null || tracks.isEmpty())
            return Collections.emptyList();
        if(playlist.isSearchResult())
            return Collections.singletonList(tracks.get(0));
        if(playlist.getSelectedTrack() != null)
            return Collections.singletonList(playlist.getSelectedTrack());
        return tracks;
    }

    /** The name of a song or track for an error line: its title, else its address. */
    private static String describe(String title, String uri)
    {
        if(title != null && !title.trim().isEmpty())
            return title;
        return uri == null ? "(unknown)" : uri;
    }

    /**
     * Collects the answers to the loads of a list of songs: each answer queues its tracks (or notes why it
     * can't) and the last answer to arrive reports the result. Each song answers once, whatever lavaplayer
     * calls on its handler, and an exception from {@code each} costs that track and nothing else.
     */
    static final class LoadRun
    {
        private final List<Song> songs;
        private final Predicate<AudioTrack> tooLong;
        private final Consumer<AudioTrack> each;
        private final Consumer<Result> done;
        private final AtomicInteger pending;
        private final AtomicInteger loaded = new AtomicInteger();
        private final List<LoadError> errors = new ArrayList<>();

        LoadRun(List<Song> songs, Predicate<AudioTrack> tooLong, Consumer<AudioTrack> each, Consumer<Result> done)
        {
            this.songs = songs;
            this.tooLong = tooLong;
            this.each = each;
            this.done = done;
            this.pending = new AtomicInteger(songs.size());
        }

        /** The handler to load the song at {@code index} with. */
        AudioLoadResultHandler handler(int index)
        {
            return new AudioLoadResultHandler()
            {
                private final AtomicBoolean answered = new AtomicBoolean();

                @Override
                public void trackLoaded(AudioTrack track)
                {
                    answer(() -> offer(index, track));
                }

                @Override
                public void playlistLoaded(AudioPlaylist playlist)
                {
                    answer(() ->
                    {
                        List<AudioTrack> tracks = tracksOf(playlist);
                        if(tracks.isEmpty())
                            fail(index, songName(index), NO_MATCHES);
                        for(AudioTrack track : tracks)
                            offer(index, track);
                    });
                }

                @Override
                public void noMatches()
                {
                    answer(() -> fail(index, songName(index), NO_MATCHES));
                }

                @Override
                public void loadFailed(FriendlyException exception)
                {
                    answer(() -> fail(index, songName(index), "Failed to load track: " + exception.getLocalizedMessage()));
                }

                private void answer(Runnable work)
                {
                    if(!answered.compareAndSet(false, true))
                        return;
                    try
                    {
                        work.run();
                    }
                    finally
                    {
                        if(pending.decrementAndGet() == 0)
                            done.accept(result());
                    }
                }
            };
        }

        private void offer(int index, AudioTrack track)
        {
            String name = describe(track.getInfo().title, track.getInfo().uri);
            if(tooLong.test(track))
            {
                fail(index, name, TOO_LONG);
                return;
            }
            try
            {
                each.accept(track);
                loaded.incrementAndGet();
            }
            catch(RuntimeException e)
            {
                String why = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                LOG.warn("Could not queue '{}' from a playlist: {}", name, why);
                LOG.debug("Stack trace of the failure to queue '{}'", name, e);
                fail(index, name, "Could not queue it: " + why);
            }
        }

        private void fail(int index, String item, String reason)
        {
            synchronized(errors)
            {
                errors.add(new LoadError(index, item, reason));
            }
        }

        private String songName(int index)
        {
            Song song = songs.get(index);
            return describe(song.getTitle(), song.getUri());
        }

        private Result result()
        {
            List<LoadError> found;
            synchronized(errors)
            {
                found = new ArrayList<>(errors);
            }
            found.sort(Comparator.comparingInt(LoadError::getIndex)); // stable: one song's errors stay in order
            return new Result(loaded.get(), found, songs.size());
        }
    }

    /** What happened when a playlist was loaded. */
    public static final class Result
    {
        private final int loaded;
        private final List<LoadError> errors;
        private final int total;

        Result(int loaded, List<LoadError> errors, int total)
        {
            this.loaded = loaded;
            this.errors = Collections.unmodifiableList(new ArrayList<>(errors));
            this.total = total;
        }

        /** How many tracks were given out. */
        public int getLoaded()
        {
            return loaded;
        }

        /** What could not be loaded, by the position of the song in the order it was loaded. */
        public List<LoadError> getErrors()
        {
            return errors;
        }

        /** How many songs (or lines of an old {@code .txt} playlist) were tried. */
        public int getTotal()
        {
            return total;
        }
    }

    /** One song, or track, that did not make it: what it was and why. */
    public static final class LoadError
    {
        private final int index;
        private final String item;
        private final String reason;

        LoadError(int index, String item, String reason)
        {
            this.index = index;
            this.item = item;
            this.reason = reason;
        }

        /** The position of the song among the ones tried, from 0. */
        public int getIndex()
        {
            return index;
        }

        /** The title of the song or track, else its address; for an old {@code .txt} playlist, the line. */
        public String getItem()
        {
            return item;
        }

        public String getReason()
        {
            return reason;
        }
    }
}
