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

import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.DelegatedAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.InternalAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A SoundCloud track SoundCloud only offers encrypted, played from YouTube instead. It keeps the SoundCloud
 * {@link AudioTrackInfo} (title, author, length, link, artwork, {@code X:} identifier), so the queue, History, likes
 * and playlists keep pointing at SoundCloud, and it names the SoundCloud source as its own.
 * <p>
 * Nothing is searched until the track starts playing: then YouTube is searched for author + " " + title, the result
 * is {@link #pick picked} and played as this track's delegate, and one INFO line names the swap. Every play searches
 * again, so a clone (repeat, History replay) never reuses a finished delegate. When YouTube finds nothing, or the
 * search fails, the track fails with {@value CksSoundCloudFormatHandler#PROTECTED_MESSAGE}.
 * <p>
 * While the delegate plays, position and duration are the YouTube video's (lavaplayer's {@link DelegatedAudioTrack});
 * the info's length stays the SoundCloud one.
 */
public class YouTubeFallbackTrack extends DelegatedAudioTrack
{
    private static final Logger log = LoggerFactory.getLogger(YouTubeFallbackTrack.class);
    /** How many of the first results are compared by length. */
    static final int TOP_RESULTS = 5;
    /** How far a result's length may be from the SoundCloud length, as a share of the SoundCloud length. */
    static final double LENGTH_TOLERANCE = 0.15;

    /** Searches YouTube. */
    public interface YouTubeSearch
    {
        /**
         * @param query what to search for (author + " " + title), without a search prefix
         * @return the tracks found, best first; empty when nothing is found
         */
        List<AudioTrack> search(String query) throws Exception;
    }

    private final YouTubeSearch search;
    private final AudioSourceManager source;

    /**
     * @param soundCloudInfo the protected SoundCloud track's info, kept as this track's own
     * @param search         searches YouTube when the track starts playing
     * @param source         the source this track belongs to: the bot's SoundCloud source, which stores and restores it
     */
    public YouTubeFallbackTrack(AudioTrackInfo soundCloudInfo, YouTubeSearch search, AudioSourceManager source)
    {
        super(soundCloudInfo);
        this.search = search;
        this.source = source;
    }

    /**
     * The YouTube result to play for a SoundCloud track this long: among the first {@value #TOP_RESULTS}, the first
     * whose length is within 15 % of {@code soundCloudLengthMs}, else the first of them that is not a live stream
     * (also when the SoundCloud length is unknown). A live stream is never picked: its length is unknown, so it is
     * never "within 15 %", and it would never end.
     *
     * @return null when there are no results, or none of the first {@value #TOP_RESULTS} is a normal video
     */
    static AudioTrack pick(List<AudioTrack> results, long soundCloudLengthMs)
    {
        if(results == null || results.isEmpty())
            return null;
        int top = Math.min(TOP_RESULTS, results.size());
        for(int i = 0; i < top; i++)
            if(closeInLength(results.get(i), soundCloudLengthMs))
                return results.get(i);
        for(int i = 0; i < top; i++)
            if(results.get(i) != null && !results.get(i).getInfo().isStream)
                return results.get(i);
        return null;
    }

    /** The link without its query string, for logging: signed SoundCloud links carry their signature there. */
    static String withoutQuery(String uri)
    {
        if(uri == null)
            return null;
        int query = uri.indexOf('?');
        return query < 0 ? uri : uri.substring(0, query);
    }

    @Override
    public void process(LocalAudioTrackExecutor executor) throws Exception
    {
        AudioTrack match = pick(searchYouTube(), getInfo().length);
        if(match == null)
            throw noMatch(null);
        log.info("SoundCloud protects {}; playing YouTube {} instead", withoutQuery(getInfo().uri),
                match.getIdentifier());
        processDelegate((InternalAudioTrack) match, executor);
    }

    @Override
    public AudioSourceManager getSourceManager()
    {
        return source;
    }

    @Override
    protected AudioTrack makeShallowClone()
    {
        return new YouTubeFallbackTrack(getInfo(), search, source);
    }

    private List<AudioTrack> searchYouTube() throws InterruptedException
    {
        List<AudioTrack> results;
        try
        {
            results = search.search(query());
        }
        catch(InterruptedException e)
        {
            throw e;
        }
        catch(Exception e)
        {
            throw noMatch(e);
        }
        return results == null ? Collections.emptyList() : results;
    }

    private String query()
    {
        AudioTrackInfo info = getInfo();
        return info.author == null || info.author.isEmpty() ? info.title : info.author + " " + info.title;
    }

    private static boolean closeInLength(AudioTrack result, long soundCloudLengthMs)
    {
        if(result == null || soundCloudLengthMs <= 0 || soundCloudLengthMs == Long.MAX_VALUE)
            return false;
        long length = result.getInfo().length;
        return Math.abs((double) length - soundCloudLengthMs) <= LENGTH_TOLERANCE * soundCloudLengthMs;
    }

    private static FriendlyException noMatch(Throwable cause)
    {
        return new FriendlyException(CksSoundCloudFormatHandler.PROTECTED_MESSAGE, FriendlyException.Severity.COMMON,
                cause);
    }
}
