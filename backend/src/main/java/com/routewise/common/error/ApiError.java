package com.routewise.common.error;

import java.time.Instant;
import java.util.List;

/**
 * The single error shape for every failed request.
 *
 * <p>{@code message} is shown to the user as-is, so it is written in Turkish and
 * states what happened and what to do about it. {@code code} is the stable,
 * machine-readable identifier the frontend branches on; never branch on the
 * message text.
 *
 * @param fieldErrors populated only for validation failures, empty otherwise
 */
public record ApiError(
        String code,
        String message,
        List<FieldError> fieldErrors,
        String traceId,
        Instant timestamp) {

    public record FieldError(String field, String message) {}

    public static ApiError of(ErrorCode code, String message, String traceId) {
        return new ApiError(code.name(), message, List.of(), traceId, Instant.now());
    }

    public static ApiError of(ErrorCode code, String message, List<FieldError> fieldErrors, String traceId) {
        return new ApiError(code.name(), message, List.copyOf(fieldErrors), traceId, Instant.now());
    }
}
