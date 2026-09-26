package com.github.lisama569.versionedconfigreleasestorejava.api;

public record UpdateEntryRequest(String value, String description, String category,
                                 Long expectedVersion, String expectedValue) {
}
