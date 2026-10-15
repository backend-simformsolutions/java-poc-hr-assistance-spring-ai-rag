package com.example.hrbot.dto;

import java.util.List;

/**
 * Structured response requested from the language model when attributing a streamed
 * answer to the sources it was written from.
 *
 * <p>The counterpart of {@link CitedAnswer} for an answer that already exists: the model
 * reports labels only, and each label is verified against the set supplied to it.
 *
 * @param citations labels of the sources that state the answer's facts, e.g.
 *                  {@code ["S1", "S3"]}
 */
public record SourceAttribution(List<String> citations) {

    /** Never null, so callers can iterate without a guard. */
    public List<String> citations() {
        return citations == null ? List.of() : citations;
    }
}
