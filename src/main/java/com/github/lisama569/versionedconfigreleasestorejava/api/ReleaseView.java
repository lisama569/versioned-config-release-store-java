package com.github.lisama569.versionedconfigreleasestorejava.api;

import java.time.Instant;
import java.util.List;

public record ReleaseView(long seq, String requestId, String state, List<String> entryKeys,
                          String remark, Long publishedRevisionSeq, int formatVersion,
                          Instant createdAt, Instant updatedAt) {
}
