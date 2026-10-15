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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stores the background pictures people upload for their themes in one folder ({@code Themes/images/}).
 * Every upload is treated as hostile: its size is capped while it is being written, its type comes from
 * its first bytes only (JPEG, PNG, GIF and WebP; never SVG or anything else a browser could run), and it
 * is stored under a name this class makes up (32 random hex characters plus the detected extension), so
 * nothing about the upload's own name, type or content decides where or how it is saved or served.
 * Pictures are served by that secret name without a login, so the name pattern is the access rule.
 */
public class ThemeImages
{
    private static final Logger LOG = LoggerFactory.getLogger(ThemeImages.class);

    /** The temp files of uploads that are still being written; a hard kill can leave some behind. */
    private static final String TEMP_GLOB = ".pic-*.tmp";
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final int NAME_BYTES = 16;
    /** Enough of the start of a file to tell every supported type apart: a WebP needs 12 bytes. */
    private static final int HEAD_BYTES = 12;
    private static final int BUFFER = 8192;

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] GIF87A = ascii("GIF87a");
    private static final byte[] GIF89A = ascii("GIF89a");
    private static final byte[] RIFF = ascii("RIFF");
    private static final byte[] WEBP = ascii("WEBP");

    private static final String NOT_A_PICTURE = "That file isn't a picture we can use (JPG, PNG, WebP or GIF)";
    /** The message for a picture over the size limit; the store uses it too when an upload says up front that it is too big. */
    static final String TOO_LARGE = "Pictures can be up to 8 MB";

    private final Path dir;
    private final Random random;

    /** Pictures live directly in {@code dir}, which is created when the first one is saved. */
    public ThemeImages(Path dir)
    {
        this(dir, new SecureRandom());
    }

    /** For tests: a predictable source of names. */
    ThemeImages(Path dir, Random random)
    {
        this.dir = Objects.requireNonNull(dir);
        this.random = Objects.requireNonNull(random);
    }

    private static byte[] ascii(String text)
    {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * The extension ({@code jpg}, {@code png}, {@code gif} or {@code webp}) for a file that starts with
     * {@code head}, or {@code null} when it is none of those. Only the signature is looked at: a WebP is
     * {@code RIFF}, any four bytes, then {@code WEBP}, so other RIFF files (WAVE, AVI) are not pictures.
     */
    public static String extensionOf(byte[] head)
    {
        if(head == null)
            return null;
        if(startsWith(head, 0, JPEG))
            return "jpg";
        if(startsWith(head, 0, PNG))
            return "png";
        if(startsWith(head, 0, GIF87A) || startsWith(head, 0, GIF89A))
            return "gif";
        if(startsWith(head, 0, RIFF) && startsWith(head, 8, WEBP))
            return "webp";
        return null;
    }

    private static boolean startsWith(byte[] data, int offset, byte[] signature)
    {
        if(data.length < offset + signature.length)
            return false;
        for(int i = 0; i < signature.length; i++)
            if(data[offset + i] != signature[i])
                return false;
        return true;
    }

    /** The {@code Content-Type} for a stored picture's name; anything that isn't one of the four types is plain bytes. */
    public static String contentType(String name)
    {
        String extension = name == null ? "" : name.substring(name.lastIndexOf('.') + 1);
        switch(extension)
        {
            case "jpg":
                return "image/jpeg";
            case "png":
                return "image/png";
            case "webp":
                return "image/webp";
            case "gif":
                return "image/gif";
            default:
                return "application/octet-stream";
        }
    }

    /**
     * Saves an uploaded picture and returns its new name. Reads at most {@code maxBytes + 1} bytes: the
     * upload goes to a temp file in the folder while it is counted, and is refused with a
     * {@link PictureTooLargeException} the moment it goes over {@code maxBytes}. Its first bytes must then
     * be a JPEG, PNG, GIF or WebP, otherwise an {@link IllegalArgumentException} is thrown. Either way (and
     * on any I/O failure) the temp file is deleted and nothing is left behind. The caller closes the stream.
     */
    public String save(InputStream in, long maxBytes)
    {
        if(maxBytes < 0)
            throw new IllegalArgumentException("maxBytes can't be negative");
        Path temp = dir.resolve(".pic-" + UUID.randomUUID() + ".tmp");
        boolean moved = false;
        try
        {
            Files.createDirectories(dir);
            byte[] head = new byte[HEAD_BYTES];
            int headLength = 0;
            long total = 0;
            try(OutputStream out = Files.newOutputStream(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))
            {
                byte[] buffer = new byte[BUFFER];
                while(true)
                {
                    // never ask for more than one byte past the limit (and never overflow near Long.MAX_VALUE)
                    long room = maxBytes - total;
                    int read = in.read(buffer, 0, room >= buffer.length ? buffer.length : (int) room + 1);
                    if(read < 0)
                        break;
                    total += read;
                    if(total > maxBytes)
                        throw new PictureTooLargeException(TOO_LARGE);
                    if(headLength < HEAD_BYTES)
                    {
                        int keep = Math.min(read, HEAD_BYTES - headLength);
                        System.arraycopy(buffer, 0, head, headLength, keep);
                        headLength += keep;
                    }
                    out.write(buffer, 0, read);
                }
            }
            String extension = extensionOf(Arrays.copyOf(head, headLength));
            if(extension == null)
                throw new IllegalArgumentException(NOT_A_PICTURE);
            String name = moveIntoPlace(temp, extension);
            moved = true;
            return name;
        }
        catch(IOException e)
        {
            throw new UncheckedIOException("Could not save the picture", e);
        }
        finally
        {
            if(!moved)
                deleteQuietly(temp);
        }
    }

    /** Moves the temp file to a new random name that is not taken (an atomic move; a plain replace where that isn't supported). */
    private String moveIntoPlace(Path temp, String extension) throws IOException
    {
        String name;
        Path target;
        do
        {
            name = randomName() + "." + extension;
            target = dir.resolve(name);
        }
        while(Files.exists(target, LinkOption.NOFOLLOW_LINKS));
        try
        {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
        }
        catch(AtomicMoveNotSupportedException e)
        {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return name;
    }

    private String randomName()
    {
        byte[] raw = new byte[NAME_BYTES];
        random.nextBytes(raw);
        char[] name = new char[NAME_BYTES * 2];
        for(int i = 0; i < NAME_BYTES; i++)
        {
            name[2 * i] = HEX[(raw[i] >> 4) & 0xF];
            name[2 * i + 1] = HEX[raw[i] & 0xF];
        }
        return new String(name);
    }

    /** Deletes a stored picture. Quiet when it is already gone, and for anything that isn't a picture name. */
    public void delete(String name)
    {
        if(name == null || !ThemeFile.IMAGE_PATTERN.matcher(name).matches())
            return;
        deleteQuietly(dir.resolve(name));
    }

    /**
     * The file for a stored picture's name, for serving. Empty unless the name is exactly 32 lowercase hex
     * characters plus {@code .jpg}, {@code .png}, {@code .webp} or {@code .gif}, the path is directly inside
     * the folder, and the picture exists as a plain file there.
     */
    public Optional<Path> resolve(String name)
    {
        if(name == null || !ThemeFile.IMAGE_PATTERN.matcher(name).matches())
            return Optional.empty();
        Path root = dir.toAbsolutePath().normalize();
        Path file = root.resolve(name).normalize();
        if(!root.equals(file.getParent()) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
            return Optional.empty();
        return Optional.of(file);
    }

    /**
     * Deletes the temp files that an upload left behind when the bot was killed in the middle of it: those
     * last changed before {@code before}. Newer ones may belong to an upload that is still being written.
     * Never throws and never makes the folder.
     */
    public void deleteStaleTemps(Instant before)
    {
        deleteOlderThan(dir, TEMP_GLOB, before);
    }

    /** Deletes the plain files of {@code folder} that match {@code glob} and last changed before {@code before}; quiet if the folder isn't there. */
    static void deleteOlderThan(Path folder, String glob, Instant before)
    {
        if(!Files.isDirectory(folder))
            return;
        try(DirectoryStream<Path> files = Files.newDirectoryStream(folder, glob))
        {
            for(Path file : files)
            {
                try
                {
                    if(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && Files.getLastModifiedTime(file).toInstant().isBefore(before))
                        deleteQuietly(file);
                }
                catch(IOException e)
                {
                    LOG.warn("Could not check {}: {}", file, e.toString());
                }
            }
        }
        catch(IOException | RuntimeException e)
        {
            LOG.warn("Could not clean up {}: {}", folder, e.toString());
        }
    }

    private static void deleteQuietly(Path file)
    {
        try
        {
            Files.deleteIfExists(file);
        }
        catch(IOException e)
        {
            LOG.warn("Could not delete {}: {}", file, e.toString());
        }
    }
}
