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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Removed songs that can still be put back, for a minute: each one is remembered behind a random token, which is the
 * only way to ask for it, and works once. At most {@link #MAX_UNDO} are remembered at once; the one that expires first
 * makes room. Only what was remembered is used to put a song back: nothing the client sends with the token is believed.
 * Safe to use from many threads.
 *
 * @param <T> what a token stands for: the song and where it was
 */
final class UndoTokens<T>
{
    /** The message for a token that was never given out, was used, has expired, or is someone else's. */
    static final String TOO_LATE = "Too late to undo";
    /** How long a removed song can be put back. */
    private static final Duration UNDO_WINDOW = Duration.ofSeconds(60);
    /** Removals remembered for Undo at once. */
    private static final int MAX_UNDO = 1000;

    private final Clock clock;
    /** Guarded by itself. */
    private final Map<String, Removal<T>> removals = new HashMap<>();

    /** @param clock times the Undo window */
    UndoTokens(Clock clock)
    {
        this.clock = clock;
    }

    /** Remembers a removal for a minute; returns its token. */
    String remember(T removal)
    {
        Instant now = clock.instant();
        String token = UUID.randomUUID().toString(); // from a SecureRandom
        synchronized(removals)
        {
            removals.values().removeIf(each -> !now.isBefore(each.expires));
            while(removals.size() >= MAX_UNDO)
            {
                String first = null;
                for(Map.Entry<String, Removal<T>> each : removals.entrySet())
                    if(first == null || each.getValue().expires.isBefore(removals.get(first).expires))
                        first = each.getKey();
                removals.remove(first);
            }
            removals.put(token, new Removal<>(removal, now.plus(UNDO_WINDOW)));
        }
        return token;
    }

    /**
     * Puts back the removal of this token with {@code undo}, once, and returns what {@code undo} returns. When
     * {@code undo} throws, the token stays usable until its minute is up (the playlist may come back, or have room
     * again), and the exception goes on.
     *
     * @param mayUse whether the person asking may use this removal; a token they may not use is left alone for its owner
     * @throws NoSuchElementException {@link #TOO_LATE}, for a token there is no removal for, or one past its minute,
     *         or one {@code mayUse} refuses
     */
    <R> R redeem(String token, Predicate<T> mayUse, Function<T, R> undo)
    {
        Removal<T> removal;
        synchronized(removals)
        {
            removal = removals.get(token);
            if(removal == null || !mayUse.test(removal.value))
                throw new NoSuchElementException(TOO_LATE);
            removals.remove(token);
        }
        if(!clock.instant().isBefore(removal.expires))
            throw new NoSuchElementException(TOO_LATE);
        try
        {
            return undo.apply(removal.value);
        }
        catch(RuntimeException e)
        {
            synchronized(removals)
            {
                removals.put(token, removal);
            }
            throw e;
        }
    }

    /** A removal and until when it can be put back. */
    private static final class Removal<T>
    {
        final T value;
        final Instant expires;

        Removal(T value, Instant expires)
        {
            this.value = value;
            this.expires = expires;
        }
    }
}
