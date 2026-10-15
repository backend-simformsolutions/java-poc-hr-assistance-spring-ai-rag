package com.example.hrbot.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Finds the document chunks most relevant to a query.
 *
 * <p>The query is embedded with the same model used at ingestion time and compared
 * against stored vectors by cosine distance, so matching is by meaning rather than by
 * keyword: "vacation days" retrieves text about annual leave. Using a different model
 * for queries than for documents would produce incomparable vectors.
 *
 * <p>Both parameters are trade-offs. A high similarity threshold discards relevant
 * chunks; a large {@code topK} fills the prompt with weaker matches that the model must
 * still read. Use {@code /api/debug/search} to see what a given setting returns.
 */
@Slf4j
@Service
public class RetrievalService {

    private final VectorStore vectorStore;
    private final int defaultTopK;
    private final double similarityThreshold;

    public RetrievalService(
            VectorStore vectorStore,
            @Value("${app.retrieval.default-top-k:5}") int defaultTopK,
            @Value("${app.retrieval.similarity-threshold:0.1}") double similarityThreshold) {
        this.vectorStore = vectorStore;
        this.defaultTopK = defaultTopK;
        this.similarityThreshold = similarityThreshold;

        log.info("RetrievalService initialised: defaultTopK={}, similarityThreshold={}",
                defaultTopK, similarityThreshold);
    }

    /**
     * Retrieves the most relevant chunks for a query.
     *
     * @param query the search text
     * @param topK  number of results, or {@code null} to use the configured default
     * @return documents in descending relevance order, each carrying its similarity score
     */
    public List<Document> retrieveDocuments(String query, Integer topK) {
        int effectiveTopK = topK != null ? topK : defaultTopK;

        log.debug("Retrieving chunks for query: '{}' (topK={}, threshold={})",
                truncate(query, 100), effectiveTopK, similarityThreshold);

        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(effectiveTopK)
                .similarityThreshold(similarityThreshold)
                .build();

        return vectorStore.similaritySearch(request);
    }

    /** The minimum similarity score applied to every search. */
    public double similarityThreshold() {
        return similarityThreshold;
    }

    /** The number of chunks retrieved when a request does not specify one. */
    public int defaultTopK() {
        return defaultTopK;
    }

    private String truncate(String text, int maxLength) {
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "...";
    }
}
