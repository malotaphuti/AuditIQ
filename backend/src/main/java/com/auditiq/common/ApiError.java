package com.auditiq.common;

import java.time.Instant;
import java.util.Map;

/** Uniform error body returned by every endpoint. */
public record ApiError(Instant timestamp, int status, String error, String message, String path,
                       Map<String, String> fieldErrors) {

    public static ApiError of(int status, String error, String message, String path) {
        return new ApiError(Instant.now(), status, error, message, path, null);
    }
}
