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

import io.javalin.http.Context;
import io.javalin.http.util.MultipartUtil;
import jakarta.servlet.MultipartConfigElement;

/**
 * Size limit for multipart uploads. Javalin's own multipart settings have no limit at all, and {@code maxRequestSize}
 * only covers {@code ctx.body()}, so without this any member could write as much as they like to the temp folder before
 * an upload route got to refuse it.
 */
final class UploadLimit
{
    /** Parts up to this size stay in memory; bigger ones are spooled to the temp folder while they are read. */
    private static final int IN_MEMORY_BYTES = 64 * 1024;

    private UploadLimit() {}

    /**
     * True when the upload announces more than {@code maxBytes}: nothing of it has been read and the route should refuse it.
     * Otherwise gives the limit to Jetty, which stops reading at it (for an upload that announces no length), and returns
     * false; reading the parts then throws {@link IllegalStateException} for an upload that goes over. Call it before
     * anything reads the body.
     */
    static boolean tooLarge(Context ctx, long maxBytes)
    {
        if(ctx.req().getContentLengthLong() > maxBytes)
            return true;
        ctx.req().setAttribute(MultipartUtil.MULTIPART_CONFIG_ATTRIBUTE,
                new MultipartConfigElement(System.getProperty("java.io.tmpdir"), -1L, maxBytes, IN_MEMORY_BYTES));
        return false;
    }
}
