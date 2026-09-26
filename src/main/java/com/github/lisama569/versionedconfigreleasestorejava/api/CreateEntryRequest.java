package com.github.lisama569.versionedconfigreleasestorejava.api;

public record CreateEntryRequest(String key, String value, String description, String category) {
}
