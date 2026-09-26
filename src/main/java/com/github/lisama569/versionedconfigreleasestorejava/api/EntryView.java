package com.github.lisama569.versionedconfigreleasestorejava.api;

import java.time.Instant;

public record EntryView(long seq, String key, String value, String description, String category,
                        EntryStatusView status, long version, int formatVersion,
                        Instant createdAt, Instant updatedAt) {
    public enum EntryStatusView { DRAFT, ARCHIVED }
}
