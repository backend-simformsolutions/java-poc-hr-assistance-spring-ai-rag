package com.example.hrbot.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RetrievedChunk page accessors")
class RetrievedChunkTest {

    private RetrievedChunk withMetadata(Map<String, Object> metadata) {
        return new RetrievedChunk("text", 0.8, "policy.pdf", 3, metadata);
    }

    @Test
    void readsPageRangeFromMetadata() {
        RetrievedChunk chunk = withMetadata(Map.of("pageNumber", 2, "pageNumberEnd", 3));

        assertThat(chunk.pageNumber()).isEqualTo(2);
        assertThat(chunk.pageNumberEnd()).isEqualTo(3);
    }

    @Test
    void endPageDefaultsToStartPageWhenAbsent() {
        RetrievedChunk chunk = withMetadata(Map.of("pageNumber", 4));

        assertThat(chunk.pageNumberEnd()).isEqualTo(4);
    }

    @Test
    void reportsUnknownWhenPageMetadataIsMissing() {
        RetrievedChunk chunk = withMetadata(Map.of("documentName", "policy.pdf"));

        assertThat(chunk.pageNumber()).isEqualTo(RetrievedChunk.UNKNOWN_PAGE);
        assertThat(chunk.pageNumberEnd()).isEqualTo(RetrievedChunk.UNKNOWN_PAGE);
    }

    @Test
    void toleratesNullMetadata() {
        RetrievedChunk chunk = new RetrievedChunk("text", 0.5, "policy.pdf", 0, null);

        assertThat(chunk.pageNumber()).isEqualTo(RetrievedChunk.UNKNOWN_PAGE);
    }

    /** PostgreSQL JSON metadata may deserialise numbers as Long rather than Integer. */
    @Test
    void acceptsAnyNumericType() {
        RetrievedChunk chunk = withMetadata(Map.of("pageNumber", 7L, "pageNumberEnd", 8.0));

        assertThat(chunk.pageNumber()).isEqualTo(7);
        assertThat(chunk.pageNumberEnd()).isEqualTo(8);
    }
}
