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

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.BasicAudioPlaylist;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The SoundCloud source the bot registers. Behind it is lavaplayer's own SoundCloud source with
 * {@link CksSoundCloudFormatHandler} plugged in and every other setting as {@code createDefault()} gives them
 * (search allowed, preview tracks kept); every call goes to that source.
 * <p>
 * What it loads is handed out unchanged, except tracks SoundCloud only offers encrypted
 * ({@link CksSoundCloudFormatHandler#isProtected}): each becomes a {@link YouTubeFallbackTrack} with the same info,
 * whether it was loaded on its own, inside a set or liked tracks, on a search result page (in its place, the other
 * results untouched, so one protected result no longer breaks a whole {@code scsearch}), or restored from storage.
 * Loading never searches YouTube: a fallback track searches ({@code ytsearch:} + author + " " + title, through the
 * bot's YouTube source) only when it starts playing.
 * <p>
 * A fallback track is stored like any SoundCloud track (its info, the {@code X:} identifier, under this source's
 * name; lavaplayer's SoundCloud source writes nothing more), so it is restored as a protected SoundCloud track and
 * from there as a fallback track again.
 * <p>
 * It does not implement lavaplayer's {@code HttpConfigurable}, which lavaplayer's own sources do: the bot configures
 * no HTTP settings on its player manager today, but if it ever calls {@code setHttpRequestConfigurator} or
 * {@code setHttpBuilderConfigurator} on the manager, that would not reach SoundCloud (the wrapped source keeps its
 * defaults) until this class passes it on.
 */
public class SoundCloudSource implements AudioSourceManager
{
    static final String NAME = "soundcloud";
    private static final String YOUTUBE_SEARCH = "ytsearch:";

    private final AudioSourceManager soundCloud;
    private final AudioSourceManager youtube;
    private final AudioPlayerManager manager;

    /**
     * @param youtube the bot's YouTube source, which protected tracks are searched on
     * @param manager the player manager the YouTube searches are made for
     */
    public SoundCloudSource(AudioSourceManager youtube, AudioPlayerManager manager)
    {
        this(lavaplayerSource(), youtube, manager);
    }

    /** With {@code soundCloud} in place of lavaplayer's source, for tests. */
    SoundCloudSource(AudioSourceManager soundCloud, AudioSourceManager youtube, AudioPlayerManager manager)
    {
        this.soundCloud = soundCloud;
        this.youtube = youtube;
        this.manager = manager;
    }

    /**
     * lavaplayer's SoundCloud source with the bot's format handler. The AAC decoder downloads with the source's own
     * HTTP interface (it adds the client id); the handler is made before the source, so it reads it from a holder.
     */
    private static SoundCloudAudioSourceManager lavaplayerSource()
    {
        AtomicReference<SoundCloudAudioSourceManager> built = new AtomicReference<>();
        SoundCloudAudioSourceManager source = SoundCloudAudioSourceManager.builder()
                .withFormatHandler(new CksSoundCloudFormatHandler(() -> built.get().getHttpInterface()))
                .build();
        built.set(source);
        return source;
    }

    @Override
    public String getSourceName()
    {
        return NAME;
    }

    @Override
    public AudioItem loadItem(AudioPlayerManager playerManager, AudioReference reference)
    {
        AudioItem item = soundCloud.loadItem(playerManager, reference);
        if(item instanceof AudioTrack)
            return swap((AudioTrack) item);
        if(item instanceof AudioPlaylist)
            return swapAll((AudioPlaylist) item);
        return item;
    }

    @Override
    public boolean isTrackEncodable(AudioTrack track)
    {
        return soundCloud.isTrackEncodable(track);
    }

    @Override
    public void encodeTrack(AudioTrack track, DataOutput output) throws IOException
    {
        soundCloud.encodeTrack(track, output);
    }

    @Override
    public AudioTrack decodeTrack(AudioTrackInfo trackInfo, DataInput input) throws IOException
    {
        return swap(soundCloud.decodeTrack(trackInfo, input));
    }

    @Override
    public void shutdown()
    {
        soundCloud.shutdown();
    }

    /** A protected track as a fallback track; any other track (or null) as it is. */
    private AudioTrack swap(AudioTrack track)
    {
        if(track == null || !CksSoundCloudFormatHandler.isProtected(track))
            return track;
        return new YouTubeFallbackTrack(track.getInfo(), this::searchYouTube, this);
    }

    /** The same playlist when nothing in it is protected, else a copy with the protected tracks swapped in place. */
    private AudioPlaylist swapAll(AudioPlaylist playlist)
    {
        List<AudioTrack> tracks = playlist.getTracks();
        AudioTrack selected = playlist.getSelectedTrack();
        boolean anyProtected = selected != null && CksSoundCloudFormatHandler.isProtected(selected);
        for(AudioTrack track : tracks)
            anyProtected |= track != null && CksSoundCloudFormatHandler.isProtected(track);
        if(!anyProtected)
            return playlist;

        List<AudioTrack> swapped = new ArrayList<>(tracks.size());
        AudioTrack swappedSelected = null;
        for(AudioTrack track : tracks)
        {
            AudioTrack replacement = swap(track);
            swapped.add(replacement);
            if(selected != null && track == selected)
                swappedSelected = replacement;
        }
        if(selected != null && swappedSelected == null)
            swappedSelected = swap(selected);
        return new BasicAudioPlaylist(playlist.getName(), swapped, swappedSelected, playlist.isSearchResult());
    }

    private List<AudioTrack> searchYouTube(String query)
    {
        AudioItem found = youtube.loadItem(manager, new AudioReference(YOUTUBE_SEARCH + query, null));
        if(found instanceof AudioPlaylist)
            return ((AudioPlaylist) found).getTracks();
        if(found instanceof AudioTrack)
            return Collections.singletonList((AudioTrack) found);
        return Collections.emptyList();
    }
}
