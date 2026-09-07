package com.example.hrbot.dto;

import java.util.List;

/**
 * Structured response requested from the language model when citations are
 * model-attributed.
 *
 * <p>Asking for a fixed shape rather than prose is what makes attribution checkable.
 * The model reports which labelled sources it used, and each label is verified against
 * the set supplied to it; a label outside that set is rejected.
 *
 * <p>Deserialised by Spring AI's structured-output converter, which appends the expected
 * JSON schema to the prompt and parses the reply.
 *
 * @param answer    the answer text, with no source names or citation markers in it
 * @param citations labels of the sources the model used, e.g. {@code ["S1", "S3"]}.
 *                  Empty when the answer is a refusal, since nothing was used
 */
public record CitedAnswer(
        String answer,
        List<String> citations
) {
    /** Never null, so callers can iterate without a guard. */
    public List<String> citations() {
        return citations == null ? List.of() : citations;
    }
}
