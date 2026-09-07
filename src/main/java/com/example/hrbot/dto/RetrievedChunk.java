package com.example.hrbot.dto;

import java.util.Map;

/**
 * A single chunk returned by vector similarity search, with its provenance.
 *
 * <p>Exposing retrieval detail alongside every answer is what makes a RAG pipeline
 * debuggable. When an answer looks wrong, these fields distinguish the two very
 * different causes: retrieval returned the wrong text, or the model mishandled the
 * right text.
 *
 * @param content         the chunk text that was supplied to the model
 * @param similarityScore cosine similarity to the query, 0.0-1.0; a low top score means
 *                        the corpus probably does not contain the answer
 * @param documentName    source document name
 * @param chunkIndex      zero-based chunk position within the document. An internal
 *                        identifier: it depends on the chunking configuration and is
 *                        renumbered whenever documents are re-ingested, so it is useful
 *                        for debugging but must not be shown to end users as a citation
 * @param metadata        all metadata stored with the chunk
 */
public record RetrievedChunk(
        String content,
        double similarityScore,
        String documentName,
        int chunkIndex,
        Map<String, Object> metadata
) {

    /** Returned when a chunk carries no page information. */
    public static final int UNKNOWN_PAGE = -1;

    /**
     * First source page this chunk covers, or {@link #UNKNOWN_PAGE} if unknown.
     *
     * <p>Page numbers are the citation shown to users. Unlike {@link #chunkIndex} they
     * are a property of the source document: stable across re-ingestion, meaningful
     * outside this application, and verifiable by opening the document.
     */
    public int pageNumber() {
        return intFromMetadata("pageNumber");
    }

    /**
     * Last source page this chunk covers.
     *
     * <p>Equal to {@link #pageNumber()} for a chunk contained within one page, and
     * greater for a chunk that spans a page break. Reporting the range avoids
     * attributing text to the page a chunk merely started on.
     */
    public int pageNumberEnd() {
        int end = intFromMetadata("pageNumberEnd");
        return end > 0 ? end : pageNumber();
    }

    private int intFromMetadata(String key) {
        Object value = metadata == null ? null : metadata.get(key);
        return (value instanceof Number number) ? number.intValue() : UNKNOWN_PAGE;
    }
}
