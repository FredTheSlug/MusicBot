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
package com.jagrosh.jmusicbot.web;

import com.jagrosh.jmusicbot.playlist.LavaTrackResolver;
import com.jagrosh.jmusicbot.playlist.LikesStore;
import com.jagrosh.jmusicbot.playlist.PlaylistFile;
import com.jagrosh.jmusicbot.playlist.PlaylistFile.Song;
import com.jagrosh.jmusicbot.playlist.PlaylistStore;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.json.JSONObject;

/**
 * What a web client may put in a playlist or in its likes (round 1's spec section 9, R16; round 2's section 3): a
 * song's {@code uri} is an http(s) link, a {@code ytsearch:} / {@code ytmsearch:} / {@code scsearch:} search, or a
 * local file that is already stored, in some playlist or in someone's likes (those come from the old playlists; a
 * client can't make the bot read a file of its choosing). Whether a uri is stored is asked of a predicate, and only
 * for a uri that is neither a link nor a search: most requests have no local file in them.
 */
final class SongInput
{
    private static final Pattern WEB_LINK = Pattern.compile("https?://[^\\s/?#]+\\S*", Pattern.CASE_INSENSITIVE);
    private static final String[] SEARCH_PREFIXES = {"ytsearch:", "ytmsearch:", "scsearch:"};

    private SongInput()
    {
    }

    /**
     * A clean song from what a client sent: the uri (trimmed), the title (the uri when there is none), the author,
     * the length (0 when it is no number, or below 0) and the artwork link, kept only when it is http(s); a YouTube
     * video without one gets its standard thumbnail. Anything else in the object (id, who added it and when,
     * unresolved) is not read: the store decides those.
     *
     * @param alreadyStored whether a uri is already stored somewhere; asked only for a uri that is no link or search
     * @param refused the message when the uri may not be taken
     * @throws IllegalArgumentException with {@code refused}, when the uri is not {@link #allowed}
     */
    static Song song(JSONObject json, Predicate<String> alreadyStored, String refused)
    {
        String uri = PlaylistRoutes.text(json, "uri");
        uri = uri == null ? "" : uri.trim();
        if(!allowed(uri, alreadyStored))
            throw new IllegalArgumentException(refused);
        Song song = new Song();
        song.setUri(uri);
        String title = PlaylistRoutes.text(json, "title");
        song.setTitle(title == null || title.trim().isEmpty() ? uri : title);
        song.setAuthor(PlaylistRoutes.text(json, "author"));
        Object duration = json.opt("durationMs");
        song.setDurationMs(duration instanceof Number ? Math.max(0L, ((Number) duration).longValue()) : 0L);
        String artwork = PlaylistRoutes.text(json, "artworkUrl");
        // the queue, search and history rows carry no artwork: a YouTube video gets its standard thumbnail
        song.setArtworkUrl(artwork != null && isWebLink(artwork.trim()) ? artwork.trim()
                : LavaTrackResolver.youtubeThumbnail(LavaTrackResolver.youtubeIdOf(uri), uri));
        return song;
    }

    /**
     * True when a web client may store this uri: an http(s) link, a {@code ytsearch:} / {@code ytmsearch:} /
     * {@code scsearch:} search, or a uri {@code alreadyStored} says is stored. Never one with control characters.
     */
    static boolean allowed(String uri, Predicate<String> alreadyStored)
    {
        if(uri.isEmpty() || hasControlCharacters(uri))
            return false;
        return isWebLink(uri) || isSearch(uri) || alreadyStored.test(uri);
    }

    /**
     * The "already stored" check of one request: true when a song of some playlist in {@code playlists} has this uri,
     * or {@code storedInLikes} says someone liked it. The playlists are read when first asked, and then not again; the
     * likes are asked about a uri no playlist has, once per uri. Uris compare as they are stored (cut to size). Make a
     * new one for every request, so that it sees the stores as they are.
     */
    static Predicate<String> storedIn(PlaylistStore playlists, Predicate<String> storedInLikes)
    {
        return new Predicate<String>()
        {
            private Set<String> inPlaylists;
            private final Map<String, Boolean> inLikes = new HashMap<>();

            @Override
            public boolean test(String uri)
            {
                String wanted = PlaylistFile.cut(uri);
                if(inPlaylists == null)
                    inPlaylists = urisIn(playlists);
                return inPlaylists.contains(wanted) || inLikes.computeIfAbsent(wanted, storedInLikes::test);
            }
        };
    }

    /** True for a uri that someone has liked; every person's likes are read each time it is asked. */
    static Predicate<String> likedByAnyone(LikesStore likes)
    {
        return uri -> !likes.whoLiked(uri, null).isEmpty();
    }

    /** The uris of every song in every playlist. */
    private static Set<String> urisIn(PlaylistStore playlists)
    {
        Set<String> uris = new HashSet<>();
        for(PlaylistStore.Entry entry : playlists.tree())
            for(Song song : PlaylistRoutes.songsOf(playlists, entry))
                if(song.getUri() != null)
                    uris.add(song.getUri());
        return uris;
    }

    private static boolean isWebLink(String uri)
    {
        return !hasControlCharacters(uri) && WEB_LINK.matcher(uri).matches();
    }

    private static boolean isSearch(String uri)
    {
        for(String prefix : SEARCH_PREFIXES)
            if(uri.startsWith(prefix))
                return !uri.substring(prefix.length()).trim().isEmpty();
        return false;
    }

    private static boolean hasControlCharacters(String text)
    {
        for(int i = 0; i < text.length(); i++)
            if(Character.isISOControl(text.charAt(i)))
                return true;
        return false;
    }
}
