/*
 * Copyright 2026 Fred (https://github.com/FredTheSlug)
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
package com.jagrosh.jmusicbot.audio;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class SpotifyResolveResult
{
    private final List<String> searchQueries;
    private final String collectionTitle;
    private final String error;

    private SpotifyResolveResult(List<String> searchQueries, String collectionTitle, String error)
    {
        this.searchQueries = searchQueries == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(searchQueries));
        this.collectionTitle = collectionTitle;
        this.error = error;
    }

    public static SpotifyResolveResult success(List<String> searchQueries, String collectionTitle)
    {
        return new SpotifyResolveResult(searchQueries, collectionTitle, null);
    }

    public static SpotifyResolveResult failure(String error)
    {
        return new SpotifyResolveResult(Collections.emptyList(), null, error);
    }

    public boolean isSuccess()
    {
        return error == null && !searchQueries.isEmpty();
    }

    public List<String> searchQueries()
    {
        return searchQueries;
    }

    public String getCollectionTitle()
    {
        return collectionTitle;
    }

    public String getError()
    {
        return error;
    }
}
