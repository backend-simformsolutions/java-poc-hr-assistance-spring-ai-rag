package com.example.hrbot.dto;

import java.time.LocalDateTime;

/**
 * Error body returned by every failing endpoint.
 *
 * <p>A single shape across all failures — validation, ingestion, upstream and
 * unexpected — lets a client handle errors with one code path.
 *
 * @param status    HTTP status code, repeated in the body so clients need not read headers
 * @param error     short category, e.g. {@code "Validation Failed"}
 * @param message   what went wrong
 * @param path      the request URI that failed
 * @param timestamp when the error was produced, for log correlation
 */
public record ErrorResponse(
        int status,
        String error,
        String message,
        String path,
        LocalDateTime timestamp
) {
    /** Stamps the current time. */
    public ErrorResponse(int status, String error, String message, String path) {
        this(status, error, message, path, LocalDateTime.now());
    }
}