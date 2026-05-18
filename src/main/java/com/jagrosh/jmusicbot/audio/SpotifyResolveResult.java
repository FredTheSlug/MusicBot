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
