package com.example.hrbot.exception;

/**
 * Raised when a document cannot be ingested — an invalid upload, a PDF that cannot be
 * parsed or carries no text layer, or text that yields no chunks.
 *
 * <p>A distinct type lets {@code GlobalExceptionHandler} answer with 422 rather than
 * treating the failure as an internal error.
 */
public class DocumentProcessingException extends RuntimeException {

    public DocumentProcessingException(String message) {
        super(message);
    }

    public DocumentProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}