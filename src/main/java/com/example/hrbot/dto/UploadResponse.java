package com.example.hrbot.dto;

/**
 * Result of ingesting a document.
 *
 * @param fileName         name of the uploaded file
 * @param totalCharacters  characters extracted from the PDF after cleaning
 * @param totalChunks      chunks created and stored
 * @param message          human-readable confirmation
 * @param processingTimeMs wall-clock time for the ingestion pipeline
 */
public record UploadResponse(
        String fileName,
        int totalCharacters,
        int totalChunks,
        String message,
        long processingTimeMs
) {}