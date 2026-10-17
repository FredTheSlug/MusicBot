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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * The theme creator's mini-player preview (web/mini-preview.html) shows the desktop app's own card: the build copies the
 * mini-player's stylesheets and view.js from desktop/ui/mini-player into the bot's web/mini-player/ (pom.xml), so they
 * are never committed twice and the preview can't drift from the app.
 */
public class MiniPlayerPreviewFilesTest
{
    private static final String DESKTOP = "desktop/ui/mini-player/";
    private static final String SERVED = "/web/mini-player/";

    private static byte[] classpathFile(String path) throws IOException
    {
        try(InputStream in = MiniPlayerPreviewFilesTest.class.getResourceAsStream(path))
        {
            assertNotNull(path + " is not in the build", in);
            return in.readAllBytes();
        }
    }

    @Test
    public void miniThemesCssIsCopiedAtBuild() throws IOException
    {
        for(String name : new String[]{"themes.css", "mini.css", "view.js"})
            assertArrayEquals(name + " as the app has it", Files.readAllBytes(Paths.get(DESKTOP + name)), classpathFile(SERVED + name));
        // only what the preview needs: never the app's Tauri glue, its markup page or its tests
        for(String name : new String[]{"mini.js", "index.html", "view.test.js"})
            assertNull(name, MiniPlayerPreviewFilesTest.class.getResource(SERVED + name));
        // the page that shows them is the panel's own
        assertNotNull(MiniPlayerPreviewFilesTest.class.getResource("/web/mini-preview.html"));
        // and the sources hold no second copy
        assertFalse(Files.exists(Paths.get("src/main/resources/web/mini-player")));
    }
}
