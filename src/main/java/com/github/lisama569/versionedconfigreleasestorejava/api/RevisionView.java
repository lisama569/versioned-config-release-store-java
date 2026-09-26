package com.github.lisama569.versionedconfigreleasestorejava.api;

import java.time.Instant;

public record RevisionView(long seq, long entrySeq, String changeType, long versionFrom, long versionTo,
                           String oldValue, String newValue, String operator, Instant createdAt) {
}
