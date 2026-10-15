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
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class AtomicFilesTest
{
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Path file(String name, String text) throws IOException
    {
        Path file = tmp.getRoot().toPath().resolve(name);
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static String read(Path file) throws IOException
    {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private List<String> files() throws IOException
    {
        try(Stream<Path> list = Files.list(tmp.getRoot().toPath()))
        {
            return list.map(p -> p.getFileName().toString()).sorted().collect(Collectors.toList());
        }
    }

    @Test
    public void replacesAnExistingFileAndLeavesNoTempBehind() throws IOException
    {
        Path target = file("theme.json", "old");
        Path temp = file(".theme-1.tmp", "new");

        AtomicFiles.replace(temp, target);

        assertEquals("new", read(target));
        assertFalse(Files.exists(temp));
        assertEquals(Arrays.asList("theme.json"), files());
    }

    @Test
    public void createsTheTargetWhenThereIsNone() throws IOException
    {
        Path temp = file(".theme-1.tmp", "fresh");
        Path target = tmp.getRoot().toPath().resolve("theme.json");

        AtomicFiles.replace(temp, target);

        assertEquals("fresh", read(target));
        assertEquals(Arrays.asList("theme.json"), files());
    }

    @Test
    public void aMissingTempIsAnErrorAndTheTargetStays() throws IOException
    {
        Path target = file("theme.json", "old");

        assertThrows(NoSuchFileException.class, () -> AtomicFiles.replace(tmp.getRoot().toPath().resolve(".theme-gone.tmp"), target));

        assertEquals("old", read(target));
    }

    @Test
    public void triesAgainWhileAccessIsDenied() throws IOException
    {
        Path target = file("theme.json", "old");
        Path temp = file(".theme-1.tmp", "new");
        AtomicInteger calls = new AtomicInteger();

        // Windows says no for a few milliseconds while a reader has the file open
        AtomicFiles.replace(temp, target, (from, to) ->
        {
            if(calls.incrementAndGet() < 4)
                throw new AccessDeniedException(to.toString());
            AtomicFiles.move(from, to);
        });

        assertEquals(4, calls.get());
        assertEquals("new", read(target));
        assertEquals(Arrays.asList("theme.json"), files());
    }

    @Test
    public void givesUpAfterSixDeniedAttempts() throws IOException
    {
        Path target = file("theme.json", "old");
        Path temp = file(".theme-1.tmp", "new");
        AtomicInteger calls = new AtomicInteger();

        assertThrows(AccessDeniedException.class, () -> AtomicFiles.replace(temp, target, (from, to) ->
        {
            calls.incrementAndGet();
            throw new AccessDeniedException(to.toString());
        }));

        assertEquals(6, calls.get());
        assertEquals("old", read(target));
        // removing the temp is the caller's job
        assertEquals(new ArrayList<>(Arrays.asList(".theme-1.tmp", "theme.json")), files());
    }

    @Test
    public void otherFailuresAreNotRetried() throws IOException
    {
        Path target = file("theme.json", "old");
        Path temp = file(".theme-1.tmp", "new");
        AtomicInteger calls = new AtomicInteger();

        assertThrows(NoSuchFileException.class, () -> AtomicFiles.replace(temp, target, (from, to) ->
        {
            calls.incrementAndGet();
            throw new NoSuchFileException(from.toString());
        }));

        assertEquals(1, calls.get());
    }
}
