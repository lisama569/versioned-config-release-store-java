package com.github.lisama569.versionedconfigreleasestorejava.api;

import java.util.List;

public record CreateReleaseRequest(String requestId, List<String> entryKeys, String remark) {
}
