package com.example.hrbot.controller;

import com.example.hrbot.dto.DebugSearchResponse;
import com.example.hrbot.dto.RetrievedChunk;
import com.example.hrbot.embeddings.EmbeddingService;
import com.example.hrbot.prompts.PromptBuilder;
import com.example.hrbot.rag.ChatService;
import com.example.hrbot.rag.RetrievalService;
import com.example.hrbot.repository.DocumentChunkRepository;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Endpoints for inspecting the retrieval stage in isolation.
 *
 * <p>A language model always produces an answer, including when retrieval returned
 * nothing useful, so a wrong answer alone does not say which stage failed. These
 * endpoints expose the retrieved chunks, their scores and the assembled prompt without
 * invoking the model, which separates a retrieval problem from a generation problem.
 *
 * <p>Where a parameter is omitted, the configured default is applied and the effective
 * value is reported in the response.
 *
 * <p>They expose stored content and internal metadata, and should be secured or
 * disabled outside development — for example behind a Spring profile.
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/debug")
public class DebugController {

    /** Matches the ceiling {@code ChatRequest} places on {@code topK}. */
    private static final int MAX_TOP_K = 20;

    private final RetrievalService retrievalService;
    private final EmbeddingService embeddingService;
    private final ChatService chatService;
    private final DocumentChunkRepository chunkRepository;

    public DebugController(
            RetrievalService retrievalService,
            EmbeddingService embeddingService,
            ChatService chatService,
            DocumentChunkRepository chunkRepository) {
        this.retrievalService = retrievalService;
        this.embeddingService = embeddingService;
        this.chatService = chatService;
        this.chunkRepository = chunkRepository;
    }

    /**
     * Runs a similarity search and returns the results together with the prompt that
     * would have been sent to the model.
     *
     * @param q    the search text, named as in the query string so that a constraint
     *             violation reports the parameter the caller sent
     * @param topK chunks to retrieve, or omitted for the configured default
     */
    @GetMapping("/search")
    public ResponseEntity<DebugSearchResponse> debugSearch(
            @RequestParam("q")
            @NotBlank(message = "must not be blank")
            String q,

            @RequestParam(value = "topK", required = false)
            @Min(value = 1, message = "must be at least 1")
            @Max(value = MAX_TOP_K, message = "must be at most " + MAX_TOP_K)
            Integer topK) {

        int effectiveTopK = topK != null ? topK : retrievalService.defaultTopK();

        log.info("Debug search: query='{}', topK={}", q, effectiveTopK);

        List<Document> documents = retrievalService.retrieveDocuments(q, effectiveTopK);
        PromptBuilder.GroundedPrompt prompt = chatService.promptFor(documents, q);
        int dimensions = embeddingService.getDimensions();

        List<RetrievedChunk> chunks = documents.stream()
                .map(doc -> {
                    Map<String, Object> metadata = doc.getMetadata();
                    return new RetrievedChunk(
                            doc.getText(),
                            doc.getScore() != null ? doc.getScore() : 0.0,
                            metadata.getOrDefault("documentName", "unknown").toString(),
                            metadata.get("chunkIndex") instanceof Number index
                                    ? index.intValue() : -1,
                            metadata
                    );
                })
                .toList();

        return ResponseEntity.ok(new DebugSearchResponse(
                q,
                dimensions,
                chunks,
                prompt.system(),
                prompt.user(),
                chatService.citationMode(),
                chatService.structuredOutputSchema(),
                new DebugSearchResponse.SearchParams(
                        effectiveTopK, retrievalService.similarityThreshold())
        ));
    }

    /**
     * Runs the same search through raw SQL rather than {@code VectorStore}, embedding the
     * query explicitly and querying pgvector directly.
     *
     * <p>Both endpoints apply the same configured defaults and should return the same
     * ranking, so either can be used to confirm the other; a discrepancy points at the
     * abstraction rather than at the data.
     *
     * @param q         the search text, named as in {@link #debugSearch}
     * @param topK      chunks to retrieve, or omitted for the configured default
     * @param threshold minimum similarity score, or omitted for the configured default
     * @return the matching chunks, the parameters applied, the statement executed with
     *         its bindings explained, and the query embedding's size and leading values
     */
    @GetMapping("/search-raw")
    public ResponseEntity<Map<String, Object>> debugSearchRaw(
            @RequestParam("q")
            @NotBlank(message = "must not be blank")
            String q,

            @RequestParam(value = "topK", required = false)
            @Min(value = 1, message = "must be at least 1")
            @Max(value = MAX_TOP_K, message = "must be at most " + MAX_TOP_K)
            Integer topK,

            @RequestParam(value = "threshold", required = false)
            @DecimalMin(value = "0.0", message = "must be at least 0.0")
            @DecimalMax(value = "1.0", message = "must be at most 1.0")
            Double threshold) {

        int effectiveTopK = topK != null ? topK : retrievalService.defaultTopK();
        double effectiveThreshold = threshold != null
                ? threshold
                : retrievalService.similarityThreshold();

        log.info("Debug RAW search: query='{}', topK={}, threshold={}",
                q, effectiveTopK, effectiveThreshold);

        float[] queryEmbedding = embeddingService.embed(q);

        List<RetrievedChunk> results = chunkRepository.searchBySimilarity(
                queryEmbedding, effectiveTopK, effectiveThreshold);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("query", q);
        response.put("searchParams", new DebugSearchResponse.SearchParams(
                effectiveTopK, effectiveThreshold));
        response.put("embeddingDimensions", queryEmbedding.length);
        response.put("embeddingPreview", getEmbeddingPreview(queryEmbedding));
        response.put("resultsCount", results.size());
        response.put("results", results);
        response.put("sqlExecuted", chunkRepository.similaritySearchSql());
        response.put("sqlParameters", List.of(
                "1: query vector (" + queryEmbedding.length + " dimensions)",
                "2: query vector (same)",
                "3: threshold = " + effectiveThreshold,
                "4: query vector (same)",
                "5: limit = " + effectiveTopK
        ));
        return ResponseEntity.ok(response);
    }

    /**
     * Reports what is stored, the retrieval settings in force, and both prompt messages
     * belonging to the active citation mode.
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        List<String> documentNames = chunkRepository.getStoredDocumentNames();

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalChunks", chunkRepository.getTotalChunkCount());
        stats.put("documentCount", documentNames.size());
        stats.put("documents", documentNames);
        stats.put("defaultTopK", retrievalService.defaultTopK());
        stats.put("similarityThreshold", retrievalService.similarityThreshold());
        stats.put("citationMode", chatService.citationMode());
        stats.put("systemPrompt", chatService.activeSystemPrompt());
        stats.put("userPromptTemplate", chatService.activeUserTemplate());
        return ResponseEntity.ok(stats);
    }

    /** Renders the leading dimensions of an embedding; the full vector is too large to display. */
    private String getEmbeddingPreview(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        int previewSize = Math.min(10, embedding.length);
        for (int i = 0; i < previewSize; i++) {
            if (i > 0) sb.append(", ");
            sb.append(String.format("%.6f", embedding[i]));
        }
        sb.append(", ... (").append(embedding.length).append(" total dimensions)]");
        return sb.toString();
    }
}
