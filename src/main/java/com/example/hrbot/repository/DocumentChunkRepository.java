package com.example.hrbot.repository;

import com.example.hrbot.dto.RetrievedChunk;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Direct SQL access to the {@code vector_store} table.
 *
 * <p>Complements Spring AI's {@code VectorStore}, which owns the read and write paths
 * the application uses. This class covers what that abstraction does not expose:
 * administrative queries, and a hand-written similarity search used by the debug
 * endpoint to confirm results against the underlying table.
 *
 * <p>{@link JdbcTemplate} rather than JPA, because similarity search needs pgvector's
 * distance operators, which have no ORM mapping.
 */
@Slf4j
@Repository
public class DocumentChunkRepository {

    /**
     * Cosine similarity search.
     *
     * <p>{@code <=>} is pgvector's cosine distance operator, so {@code 1 - distance} is
     * the similarity. Results are ordered by distance ascending — nearest first. The
     * query vector is bound three times because each placeholder is a separate binding.
     *
     * <p>{@code DebugController} describes this binding order to callers, so the two
     * change together.
     */
    private static final String SIMILARITY_SEARCH_SQL = """
            SELECT id, content, metadata,
                   1 - (embedding <=> CAST(? AS vector)) as similarity_score
            FROM vector_store
            WHERE 1 - (embedding <=> CAST(? AS vector)) >= ?
            ORDER BY embedding <=> CAST(? AS vector) ASC
            LIMIT ?
            """;

    private static final TypeReference<Map<String, Object>> METADATA_TYPE =
            new TypeReference<>() {};

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public DocumentChunkRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Finds the chunks nearest to a query vector.
     *
     * @param queryEmbedding the embedded query
     * @param topK           maximum number of results
     * @param threshold      minimum similarity score, 0.0-1.0
     * @return chunks in descending similarity order
     */
    public List<RetrievedChunk> searchBySimilarity(
            float[] queryEmbedding, int topK, double threshold) {

        String vectorString = floatArrayToVectorString(queryEmbedding);

        log.debug("Executing raw similarity search: topK={}, threshold={}, vectorDims={}",
                topK, threshold, queryEmbedding.length);

        return jdbcTemplate.query(SIMILARITY_SEARCH_SQL,
                (rs, rowNum) -> {
                    String content = rs.getString("content");
                    double score = rs.getDouble("similarity_score");
                    String metadataJson = rs.getString("metadata");

                    Map<String, Object> metadata = parseMetadata(metadataJson);
                    String documentName = metadata.getOrDefault("documentName", "unknown").toString();
                    int chunkIndex = metadata.get("chunkIndex") instanceof Number index
                            ? index.intValue() : -1;

                    return new RetrievedChunk(content, score, documentName, chunkIndex, metadata);
                },
                vectorString, vectorString, threshold, vectorString, topK);
    }

    /** The statement {@link #searchBySimilarity} issues, with its placeholders intact. */
    public String similaritySearchSql() {
        return SIMILARITY_SEARCH_SQL;
    }

    /** Returns the total number of stored chunks. */
    public int getTotalChunkCount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM vector_store", Integer.class);
        return count != null ? count : 0;
    }

    /**
     * Returns the distinct names of the documents currently stored.
     *
     * <p>Rows without the key are excluded, so the caller never receives a null entry
     * among the names.
     */
    public List<String> getStoredDocumentNames() {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT metadata->>'documentName' as doc_name FROM vector_store "
                        + "WHERE metadata->>'documentName' IS NOT NULL",
                String.class);
    }

    /**
     * Removes every stored chunk.
     *
     * @return the number of rows deleted
     */
    public int deleteAllChunks() {
        int deleted = jdbcTemplate.update("DELETE FROM vector_store");
        log.info("Deleted {} chunks from vector_store", deleted);
        return deleted;
    }

    /**
     * Renders a vector in pgvector's literal form, {@code [0.1,0.2,...]}.
     *
     * <p>Built directly rather than with {@code Arrays.toString}, which inserts spaces
     * that pgvector will not parse.
     */
    private String floatArrayToVectorString(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(vector[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * Deserialises the {@code metadata} column.
     *
     * <p>A malformed value yields an empty map rather than failing the query: metadata
     * enriches a result, and losing it should not cost the caller the chunk itself.
     */
    private Map<String, Object> parseMetadata(String json) {
        if (json == null || json.isBlank()) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(json, METADATA_TYPE);
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse metadata JSON: {}", json, e);
            return new HashMap<>();
        }
    }
}
