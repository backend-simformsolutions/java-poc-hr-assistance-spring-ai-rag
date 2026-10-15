package com.example.hrbot.dto;

import java.util.List;

/**
 * Retrieval-stage detail returned by {@code /api/debug/search}.
 *
 * <p>Reports what retrieval found and the prompt that would have been sent, without
 * invoking the model, so retrieval quality can be judged on its own.
 *
 * @param query                  the query as submitted
 * @param embeddingDimensions    dimensions produced by the configured embedding model
 * @param retrievedChunks        the chunks found, in relevance order
 * @param systemPrompt           the system message — role and rules — that the active
 *                               citation mode sends, identical for every request
 * @param generatedPrompt        the user message: retrieved context and the question,
 *                               the same text {@code /api/chat} would send
 * @param citationMode           the active citation mode, which determines which pair of
 *                               templates the two messages were built from
 * @param structuredOutputSchema the schema instruction appended to the user message at
 *                               call time when the mode requests structured output;
 *                               {@code null} otherwise
 * @param searchParams           the parameters this search actually ran with, after
 *                               configured defaults were applied
 */
public record DebugSearchResponse(
        String query,
        int embeddingDimensions,
        List<RetrievedChunk> retrievedChunks,
        String systemPrompt,
        String generatedPrompt,
        String citationMode,
        String structuredOutputSchema,
        SearchParams searchParams
) {
    /**
     * @param topK                chunks requested, after defaulting
     * @param similarityThreshold minimum similarity score applied
     */
    public record SearchParams(
            int topK,
            double similarityThreshold
    ) {}
}
