package com.example.hrbot.dto;

import java.util.List;

/**
 * Answer to a chat request, together with the retrieval detail behind it.
 *
 * <p>The retrieval fields are part of the contract rather than debug extras. A wrong
 * answer in a RAG pipeline has two very different causes — retrieval returned the wrong
 * text, or the model mishandled the right text — and only the retrieved chunks
 * distinguish them.
 *
 * @param answer          the generated answer, grounded in the retrieved chunks
 * @param question        the question as asked
 * @param chunksRetrieved number of chunks retrieved for this question
 * @param sources         user-facing citations, one entry per document with its pages
 * @param retrievedChunks the chunks supplied to the model, in relevance order
 * @param processingTimeMs wall-clock time for retrieval and generation
 */
public record ChatResponse(
        String answer,
        String question,
        int chunksRetrieved,
        List<String> sources,
        List<RetrievedChunk> retrievedChunks,
        long processingTimeMs
) {}