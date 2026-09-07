package com.example.hrbot.embeddings;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

/**
 * Converts text into embedding vectors.
 *
 * <p>{@code VectorStore} embeds automatically on {@code add} and
 * {@code similaritySearch}, so this service exists for the paths that need a vector in
 * hand — the raw-SQL debug endpoint, which must embed a query before it can issue the
 * pgvector comparison itself.
 *
 * <p>Depends on the {@link EmbeddingModel} interface rather than a provider, so changing
 * provider is a matter of swapping the starter and updating configuration.
 */
@Slf4j
@Service
public class EmbeddingService {

    private final EmbeddingModel embeddingModel;

    public EmbeddingService(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
        log.info("EmbeddingService initialised with model: {}", embeddingModel.getClass().getSimpleName());
    }

    /**
     * Embeds a single text.
     *
     * @param text the text to embed
     * @return the embedding, whose length is fixed by the configured model
     */
    public float[] embed(String text) {
        log.debug("Embedding text of length {}", text.length());
        float[] embedding = embeddingModel.embed(text);
        log.debug("Generated embedding with {} dimensions", embedding.length);
        return embedding;
    }

    /**
     * Returns the number of dimensions the configured model produces.
     *
     * <p>The value must match the {@code vector} column width in the database; a
     * mismatch fails on insert.
     */
    public int getDimensions() {
        return embeddingModel.dimensions();
    }
}
