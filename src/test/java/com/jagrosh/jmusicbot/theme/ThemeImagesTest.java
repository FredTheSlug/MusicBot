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
package com.jagrosh.jmusicbot.theme;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class ThemeImagesTest
{
    private static final long EIGHT_MB = 8L * 1024 * 1024;
    private static final String NOT_A_PICTURE = "That file isn't a picture we can use (JPG, PNG, WebP or GIF)";
    private static final String TOO_LARGE = "Pictures can be up to 8 MB";
    private static final String NAME_RULE = "[0-9a-f]{32}\\.(jpg|png|webp|gif)";

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10, 'J', 'F', 'I', 'F', 0x00, 0x01};
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D, 'I', 'H', 'D', 'R'};
    private static final byte[] GIF87A = bytes("GIF87a\u0001\u0000\u0001\u0000\u0000\u0000");
    private static final byte[] GIF89A = bytes("GIF89a\u0001\u0000\u0001\u0000\u0000\u0000");
    private static final byte[] WEBP = bytes("RIFF$\u0000\u0000\u0000WEBPVP8 ");

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Path dir;
    private ThemeImages images;

    @Before
    public void setUp() throws IOException
    {
        dir = tmp.newFolder("Themes").toPath().resolve("images");
        images = new ThemeImages(dir);
    }

    private static byte[] bytes(String latin1)
    {
        return latin1.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] concat(byte[] first, byte[] second)
    {
        byte[] all = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, all, first.length, second.length);
        return all;
    }

    private static byte[] padded(byte[] prefix, long size)
    {
        byte[] all = new byte[(int) size];
        System.arraycopy(prefix, 0, all, 0, prefix.length);
        return all;
    }

    private String save(byte[] content)
    {
        return images.save(new ByteArrayInputStream(content), EIGHT_MB);
    }

    private List<String> filesInDir() throws IOException
    {
        if(!Files.isDirectory(dir))
            return new ArrayList<>();
        try(Stream<Path> list = Files.list(dir))
        {
            return list.map(p -> p.getFileName().toString()).sorted().collect(Collectors.toList());
        }
    }

    private void assertRefused(byte[] content) throws IOException
    {
        assertNull(ThemeImages.extensionOf(content));
        try
        {
            save(content);
            fail("Should have been refused: " + Arrays.toString(content));
        }
        catch(IllegalArgumentException e)
        {
            assertEquals(NOT_A_PICTURE, e.getMessage());
        }
        assertEquals("nothing left behind", new ArrayList<String>(), filesInDir());
    }

    /** Serves {@code prefix}, then zeros, up to {@code total} bytes; counts how much was handed out. */
    private static class Filler extends InputStream
    {
        private final byte[] prefix;
        private final long total;
        long served;

        Filler(byte[] prefix, long total)
        {
            this.prefix = prefix;
            this.total = total;
        }

        @Override
        public int read()
        {
            if(served >= total)
                return -1;
            int value = served < prefix.length ? prefix[(int) served] & 0xFF : 0;
            served++;
            return value;
        }

        @Override
        public int read(byte[] b, int off, int len)
        {
            if(len == 0)
                return 0;
            if(served >= total)
                return -1;
            int count = (int) Math.min(len, total - served);
            for(int i = 0; i < count; i++)
                b[off + i] = served + i < prefix.length ? prefix[(int) (served + i)] : 0;
            served += count;
            return count;
        }
    }

    @Test
    public void detectsRealHeaders() throws IOException
    {
        assertEquals("jpg", ThemeImages.extensionOf(JPEG));
        assertEquals("png", ThemeImages.extensionOf(PNG));
        assertEquals("gif", ThemeImages.extensionOf(GIF87A));
        assertEquals("gif", ThemeImages.extensionOf(GIF89A));
        assertEquals("webp", ThemeImages.extensionOf(WEBP));

        // only the signature itself counts, however short the file is after it
        assertEquals("jpg", ThemeImages.extensionOf(Arrays.copyOf(JPEG, 3)));
        assertEquals("png", ThemeImages.extensionOf(Arrays.copyOf(PNG, 8)));
        assertEquals("gif", ThemeImages.extensionOf(Arrays.copyOf(GIF89A, 6)));
        assertEquals("webp", ThemeImages.extensionOf(Arrays.copyOf(WEBP, 12)));

        // the size field of a WebP can be anything
        assertEquals("webp", ThemeImages.extensionOf(bytes("RIFFÿÿÿÿWEBP")));

        // stored with the detected extension, byte for byte
        byte[][] pictures = {JPEG, PNG, GIF87A, GIF89A, WEBP};
        String[] extensions = {"jpg", "png", "gif", "gif", "webp"};
        for(int i = 0; i < pictures.length; i++)
        {
            String name = save(pictures[i]);
            assertTrue(name, name.endsWith("." + extensions[i]));
            assertArrayEquals(pictures[i], Files.readAllBytes(dir.resolve(name)));
        }
    }

    @Test
    public void refusesOthers() throws IOException
    {
        assertRefused(bytes("<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>"));
        assertRefused(bytes("<!DOCTYPE html><html><body>hi</body></html>"));
        assertRefused(bytes("MZ\u0090\u0000\u0003\u0000\u0000\u0000"));
        assertRefused(bytes("%PDF-1.7\n"));
        assertRefused(new byte[0]);
        assertRefused(bytes("RIFF$\u0000\u0000\u0000WAVEfmt "));
        assertRefused(bytes("RIFF$\u0000\u0000\u0000AVI LIST"));
    }

    @Test
    public void refusesNearMissesAndWrappedPictures() throws IOException
    {
        assertRefused(Arrays.copyOf(JPEG, 2));
        assertRefused(Arrays.copyOf(PNG, 7));
        assertRefused(concat(Arrays.copyOf(PNG, 7), new byte[]{0x0B, 0, 0, 0}));
        assertRefused(bytes("GIF88a\u0001\u0000\u0001\u0000"));
        assertRefused(bytes("GIF89"));
        assertRefused(bytes("RIFF$\u0000\u0000\u0000WEB"));
        assertRefused(bytes("RIFX$\u0000\u0000\u0000WEBPVP8 "));
        // a picture behind anything at all is not a picture: no BOM, whitespace or markup in front
        assertRefused(concat(bytes("<?xml version=\"1.0\"?><svg/>"), JPEG));
        assertRefused(concat(bytes(" "), JPEG));
        assertRefused(concat(bytes("ï»¿"), PNG));
    }

    @Test
    public void polyglotIsStoredAsTheDetectedImageOnly() throws IOException
    {
        byte[] polyglot = concat(JPEG, bytes("<html><script>alert(document.cookie)</script></html>"));
        String name = save(polyglot);
        assertTrue(name, name.matches("[0-9a-f]{32}\\.jpg"));
        assertEquals("image/jpeg", ThemeImages.contentType(name));
        assertEquals(Arrays.asList(name), filesInDir());
        assertArrayEquals(polyglot, Files.readAllBytes(dir.resolve(name)));
        assertEquals(Optional.of(dir.resolve(name)), images.resolve(name).map(p -> p.toAbsolutePath()));
    }

    @Test
    public void tooLargeRefusedAndNothingLeft() throws IOException
    {
        try
        {
            save(padded(JPEG, EIGHT_MB + 1));
            fail("8 MB + 1 should be refused");
        }
        catch(PictureTooLargeException e)
        {
            assertEquals(TOO_LARGE, e.getMessage());
        }
        assertEquals("nothing left behind", new ArrayList<String>(), filesInDir());
    }

    @Test
    public void anUnendingUploadIsCutOffAtTheLimit() throws IOException
    {
        // stands in for an endless upload, but stops by itself so a missing cap fails the test instead of filling the disk
        Filler endless = new Filler(PNG, 4 * EIGHT_MB);
        try
        {
            images.save(endless, EIGHT_MB);
            fail("should be refused");
        }
        catch(PictureTooLargeException e)
        {
            assertEquals(TOO_LARGE, e.getMessage());
        }
        assertTrue("read " + endless.served, endless.served <= EIGHT_MB + 1);
        assertEquals("nothing left behind", new ArrayList<String>(), filesInDir());

        // a limit near Long.MAX_VALUE must not overflow
        Filler small = new Filler(PNG, PNG.length);
        String name = images.save(small, Long.MAX_VALUE);
        assertTrue(name.endsWith(".png"));
    }

    @Test
    public void tooLargeIsReportedWhateverTheContentIs() throws IOException
    {
        try
        {
            images.save(new Filler(bytes("<svg"), 1_000_000), 1000);
            fail("should be refused");
        }
        catch(PictureTooLargeException e)
        {
            assertEquals(TOO_LARGE, e.getMessage());
        }
        assertEquals(new ArrayList<String>(), filesInDir());
    }

    @Test
    public void exactlyMaxIsAccepted() throws IOException
    {
        Filler exact = new Filler(WEBP, EIGHT_MB);
        String name = images.save(exact, EIGHT_MB);
        assertTrue(name, name.endsWith(".webp"));
        assertEquals(EIGHT_MB, Files.size(dir.resolve(name)));
        assertEquals(Arrays.asList(name), filesInDir());
    }

    @Test
    public void namesAreRandomAndValid() throws IOException
    {
        byte[][] pictures = {JPEG, PNG, GIF89A, WEBP};
        Set<String> names = new HashSet<>();
        for(int i = 0; i < 60; i++)
        {
            String name = save(pictures[i % pictures.length]);
            assertTrue(name, name.matches(NAME_RULE));
            assertTrue("new name expected: " + name, names.add(name));
            assertTrue(Files.isRegularFile(dir.resolve(name)));
        }
        // the same picture twice is two files; nothing but the pictures is in the folder
        assertEquals(60, filesInDir().size());
        assertEquals(names, new HashSet<>(filesInDir()));
        for(String name : filesInDir())
            assertFalse(name, name.endsWith(".tmp"));
    }

    @Test
    public void createsItsFolderWhenMissing() throws IOException
    {
        Path deeper = tmp.getRoot().toPath().resolve("a").resolve("b").resolve("images");
        assertFalse(Files.exists(deeper));
        String name = new ThemeImages(deeper).save(new ByteArrayInputStream(JPEG), EIGHT_MB);
        assertTrue(Files.isRegularFile(deeper.resolve(name)));
    }

    @Test
    public void aTakenNameIsNeverOverwritten() throws IOException
    {
        // the same seed gives the same first draw, which is already a file
        byte[] draw = new byte[16];
        new Random(1234).nextBytes(draw);
        StringBuilder taken = new StringBuilder();
        for(byte b : draw)
            taken.append(String.format("%02x", b & 0xFF));
        taken.append(".jpg");
        Files.createDirectories(dir);
        Files.write(dir.resolve(taken.toString()), bytes("original"));

        String name = new ThemeImages(dir, new Random(1234)).save(new ByteArrayInputStream(JPEG), EIGHT_MB);

        assertNotEquals(taken.toString(), name);
        assertTrue(name.matches(NAME_RULE));
        assertArrayEquals(bytes("original"), Files.readAllBytes(dir.resolve(taken.toString())));
        assertArrayEquals(JPEG, Files.readAllBytes(dir.resolve(name)));
        assertEquals(2, filesInDir().size());
    }

    @Test
    public void aBrokenUploadLeavesNothing() throws IOException
    {
        InputStream broken = new InputStream()
        {
            private int sent;

            @Override
            public int read() throws IOException
            {
                if(sent >= JPEG.length)
                    throw new IOException("connection reset");
                return JPEG[sent++] & 0xFF;
            }
        };
        try
        {
            images.save(broken, EIGHT_MB);
            fail("should have failed");
        }
        catch(UncheckedIOException e)
        {
            assertEquals("connection reset", e.getCause().getMessage());
        }
        assertEquals(new ArrayList<String>(), filesInDir());
    }

    @Test
    public void resolveRejectsTraversal() throws IOException
    {
        String hex = "0123456789abcdef0123456789abcdef";
        // real files that a loose check would find: next to the folder, and in a sub-folder
        Files.write(dir.getParent().resolve("x.jpg"), JPEG);
        Files.write(dir.getParent().resolve(hex + ".jpg"), JPEG);
        Files.createDirectories(dir.resolve("a"));
        Files.write(dir.resolve("a").resolve("b.jpg"), JPEG);
        Files.write(dir.resolve("a").resolve(hex + ".jpg"), JPEG);
        Files.write(dir.resolve(hex + ".svg"), bytes("<svg/>"));
        Files.write(dir.resolve(hex.toUpperCase() + ".jpg"), JPEG);

        String[] bad = {
            "../x.jpg", "a/b.jpg", "a/" + hex + ".jpg", "../" + hex + ".jpg", "..\\x.jpg", "a\\b.jpg",
            hex.toUpperCase() + ".jpg", hex.substring(1) + ".jpg", hex + "0.jpg", hex + ".svg", hex + ".jpeg",
            hex + ".JPG", hex + ".jpg.svg", hex + ".jpg/", hex + ".jpg\n", hex + ".jpg\u0000.svg", " " + hex + ".jpg",
            "%2e%2e/x.jpg", "..", ".", "", "x.jpg", hex, "." + hex + ".jpg", dir.getParent().resolve("x.jpg").toString()
        };
        for(String name : bad)
            assertEquals("rejects '" + name + "'", Optional.empty(), images.resolve(name));
        assertEquals(Optional.empty(), images.resolve(null));
    }

    @Test
    public void resolveFindsASavedPictureAndNothingElse() throws IOException
    {
        String name = save(PNG);
        Optional<Path> found = images.resolve(name);
        assertTrue(found.isPresent());
        assertArrayEquals(PNG, Files.readAllBytes(found.get()));
        assertEquals(dir.toAbsolutePath(), found.get().getParent());

        // a well-formed name for a picture that isn't there
        assertEquals(Optional.empty(), images.resolve("0123456789abcdef0123456789abcdef.png"));
        // before the folder exists
        assertEquals(Optional.empty(), new ThemeImages(tmp.getRoot().toPath().resolve("nope"))
                .resolve("0123456789abcdef0123456789abcdef.png"));
    }

    @Test
    public void deleteRemovesThePictureAndIsQuietWhenMissing() throws IOException
    {
        String name = save(JPEG);
        String other = save(PNG);
        images.delete(name);
        assertEquals(Arrays.asList(other), filesInDir());
        images.delete(name);
        images.delete("0123456789abcdef0123456789abcdef.gif");
        assertEquals(Arrays.asList(other), filesInDir());
        // before the folder exists
        new ThemeImages(tmp.getRoot().toPath().resolve("nope")).delete(name);
    }

    @Test
    public void deleteOnlyTouchesPicturesInItsFolder() throws IOException
    {
        String name = save(JPEG);
        Path outside = dir.getParent().resolve("x.jpg");
        Files.write(outside, JPEG);
        Path wrongKind = dir.resolve("notes.txt");
        Files.write(wrongKind, bytes("keep"));

        images.delete("../x.jpg");
        images.delete("notes.txt");
        images.delete("");
        images.delete(null);
        images.delete(dir.getFileName().toString());

        assertTrue(Files.exists(outside));
        assertTrue(Files.exists(wrongKind));
        assertTrue(Files.exists(dir.resolve(name)));
        assertTrue(Files.isDirectory(dir));
    }

    @Test
    public void contentTypeFollowsTheExtension()
    {
        String hex = "0123456789abcdef0123456789abcdef";
        assertEquals("image/jpeg", ThemeImages.contentType(hex + ".jpg"));
        assertEquals("image/png", ThemeImages.contentType(hex + ".png"));
        assertEquals("image/webp", ThemeImages.contentType(hex + ".webp"));
        assertEquals("image/gif", ThemeImages.contentType(hex + ".gif"));
        assertEquals("application/octet-stream", ThemeImages.contentType(hex + ".svg"));
        assertEquals("application/octet-stream", ThemeImages.contentType("noextension"));
    }
}
