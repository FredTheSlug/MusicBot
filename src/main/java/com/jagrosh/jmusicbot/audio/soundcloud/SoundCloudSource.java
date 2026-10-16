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
import com.sedmelluq.discord.lavaplayer.tools.ExceptionTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterfaceManager;
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
import java.util.function.Supplier;

/**
 * The SoundCloud source the bot registers. Behind it is lavaplayer's own SoundCloud source with
 * {@link CksSoundCloudFormatHandler} plugged in and every other setting as {@code createDefault()} gives them
 * (search allowed, preview tracks kept); every call goes to that source.
 * <p>
 * What it loads is handed out unchanged (MP3 and Opus tracks are lavaplayer's own, the same instances), except two
 * kinds of track, each swapped for one of the bot's with the same info, whether it was loaded on its own, inside a
 * set or liked tracks, on a search result page (in its place, the other results untouched), or restored from storage:
 * <ul>
 *   <li>a plain AAC track ({@code A:}) becomes a {@link SoundCloudAacTrack}, which plays the stream with this source's
 *       own plain HTTP client, because lavaplayer's adds a client id to SoundCloud's signed AAC links and those then
 *       answer 403;</li>
 *   <li>a track SoundCloud only offers encrypted ({@link CksSoundCloudFormatHandler#isProtected}) becomes a
 *       {@link YouTubeFallbackTrack}, so one protected result no longer breaks a whole {@code scsearch}. Loading never
 *       searches YouTube: a fallback track searches ({@code ytsearch:} + author + " " + title, through the bot's
 *       YouTube source) only when it starts playing.</li>
 * </ul>
 * A swapped track is stored like any SoundCloud track (its info, with the {@code A:} or {@code X:} identifier, under
 * this source's name; lavaplayer's SoundCloud source writes nothing more), so it is restored as that SoundCloud track
 * and from there swapped again.
 * <p>
 * It does not implement lavaplayer's {@code HttpConfigurable}, which lavaplayer's own sources do: the bot configures
 * no HTTP settings on its player manager today, but if it ever calls {@code setHttpRequestConfigurator} or
 * {@code setHttpBuilderConfigurator} on the manager, that would not reach SoundCloud (the wrapped source and the AAC
 * streams' HTTP client keep their defaults) until this class passes it on.
 */
public class SoundCloudSource implements AudioSourceManager
{
    static final String NAME = "soundcloud";
    private static final String YOUTUBE_SEARCH = "ytsearch:";

    private final AudioSourceManager soundCloud;
    private final Supplier<String> clientId;
    private final AudioSourceManager youtube;
    private final AudioPlayerManager manager;
    /** The AAC streams' HTTP client: lavaplayer's defaults, no SoundCloud filter, so it adds nothing to a link. */
    private final HttpInterfaceManager streamHttp = HttpClientTools.createDefaultThreadLocalManager();

    /**
     * @param youtube the bot's YouTube source, which protected tracks are searched on
     * @param manager the player manager the YouTube searches are made for
     */
    public SoundCloudSource(AudioSourceManager youtube, AudioPlayerManager manager)
    {
        this(lavaplayerSource(), youtube, manager);
    }

    private SoundCloudSource(SoundCloudAudioSourceManager soundCloud, AudioSourceManager youtube,
            AudioPlayerManager manager)
    {
        this(soundCloud, soundCloud::getClientId, youtube, manager);
    }

    /** With {@code soundCloud} and its {@code clientId} in place of lavaplayer's source and client id, for tests. */
    SoundCloudSource(AudioSourceManager soundCloud, Supplier<String> clientId, AudioSourceManager youtube,
            AudioPlayerManager manager)
    {
        this.soundCloud = soundCloud;
        this.clientId = clientId;
        this.youtube = youtube;
        this.manager = manager;
    }

    /**
     * lavaplayer's SoundCloud source with the bot's format handler. The handler's AAC route (only taken by an
     * {@code A:} track this class has not swapped) downloads with the source's own HTTP interface; the handler is
     * made before the source, so it reads it from a holder.
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
        ExceptionTools.closeWithWarnings(streamHttp);
    }

    /** A plain AAC track as an AAC track, a protected one as a fallback track; any other track (or null) as it is. */
    private AudioTrack swap(AudioTrack track)
    {
        if(isAac(track))
            return new SoundCloudAacTrack(track.getInfo(), clientId, streamHttp::getInterface, this);
        if(track != null && CksSoundCloudFormatHandler.isProtected(track))
            return new YouTubeFallbackTrack(track.getInfo(), this::searchYouTube, this);
        return track;
    }

    /** Whether {@link #swap} replaces the track. */
    private static boolean isSwapped(AudioTrack track)
    {
        return isAac(track) || track != null && CksSoundCloudFormatHandler.isProtected(track);
    }

    private static boolean isAac(AudioTrack track)
    {
        return track != null && track.getIdentifier() != null
                && track.getIdentifier().startsWith(CksSoundCloudFormatHandler.AAC_PREFIX);
    }

    /** The same playlist when nothing in it is swapped, else a copy with those tracks swapped in place. */
    private AudioPlaylist swapAll(AudioPlaylist playlist)
    {
        List<AudioTrack> tracks = playlist.getTracks();
        AudioTrack selected = playlist.getSelectedTrack();
        boolean anySwapped = isSwapped(selected);
        for(AudioTrack track : tracks)
            anySwapped |= isSwapped(track);
        if(!anySwapped)
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
