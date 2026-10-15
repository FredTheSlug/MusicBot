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
package com.jagrosh.jmusicbot.utils;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Puts a finished temp file in place of a data file, so that a reader sees either the old file or the new
 * one and never half of a write. The stores ({@code PlaylistStore}, {@code ThemeStore}) write to a temp file
 * in the same folder and then call {@link #replace}.
 */
public final class AtomicFiles
{
    private static final int MOVE_ATTEMPTS = 6;

    private AtomicFiles()
    {
    }

    /** Moves one file over another; the real move is {@link #move}, tests give their own. */
    interface Mover
    {
        void move(Path from, Path to) throws IOException;
    }

    /**
     * Moves {@code temp} over {@code target} (made if it isn't there): an atomic move, or a plain replace on file
     * systems without atomic moves. Windows refuses to replace a file while a reader has it open, for a few
     * milliseconds, so an {@link AccessDeniedException} is tried again a few times before it is passed on. The
     * caller deletes {@code temp} when this throws.
     */
    public static void replace(Path temp, Path target) throws IOException
    {
        replace(temp, target, AtomicFiles::move);
    }

    static void replace(Path temp, Path target, Mover mover) throws IOException
    {
        for(int attempt = 1; ; attempt++)
        {
            try
            {
                mover.move(temp, target);
                return;
            }
            catch(AccessDeniedException e)
            {
                if(attempt == MOVE_ATTEMPTS)
                    throw e;
                try
                {
                    Thread.sleep(10L * attempt);
                }
                catch(InterruptedException interrupted)
                {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    /** One try: an atomic move over the target, or a plain replace where the file system can't do that. */
    static void move(Path temp, Path target) throws IOException
    {
        try
        {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
        }
        catch(AtomicMoveNotSupportedException e)
        {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
