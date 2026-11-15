package com.zirocraft.zirologistics.wms.security.throttling.domain;

import java.util.Objects;

/**
 * Privacy-preserving identity of one throttling bucket.
 *
 * The digest is safe to persist, but should still not be used as a metric label
 * or written to application logs.
 */
public record WmsThrottleBucketKey(
        WmsThrottleBucketType type,
        int keyVersion,
        String digestHex) {

    public WmsThrottleBucketKey {
        Objects.requireNonNull(type, "type must not be null");
        if (keyVersion < 1 || keyVersion > 65_535) {
            throw new IllegalArgumentException("keyVersion must be between 1 and 65535");
        }
        if (digestHex == null || !digestHex.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("digestHex must be a lowercase SHA-256 hex digest");
        }
    }
}
