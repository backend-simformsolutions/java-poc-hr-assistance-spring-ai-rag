package com.example.hrbot.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Incoming chat request.
 *
 * <p>Implemented as a record because a request is immutable data: the compiler
 * generates the constructor, accessors, {@code equals}, {@code hashCode} and
 * {@code toString}, and instances are safe to share across threads.
 *
 * <p>Validation annotations are enforced by Hibernate Validator when the controller
 * parameter is annotated {@code @Valid}. A violation raises
 * {@code MethodArgumentNotValidException}, which {@code GlobalExceptionHandler}
 * converts into a 400 response.
 *
 * @param question the user's question; must not be blank. Bounded in length because the
 *                 question shares the model's context window with the retrieved policy
 *                 text, and an oversized one would crowd that text out of the prompt
 * @param topK     how many chunks to retrieve, or {@code null} to use the configured
 *                 default. Bounded to keep a single request from monopolising the
 *                 model's context window
 */
public record ChatRequest(

        @NotBlank(message = "Question must not be blank")
        @Size(max = 1000, message = "Question must be at most 1000 characters")
        String question,

        @Min(value = 1, message = "topK must be at least 1")
        @Max(value = 20, message = "topK must be at most 20")
        Integer topK
) {
    /**
     * The caller's requested {@code topK}, or {@code null} if unspecified.
     *
     * <p>Returning {@code null} keeps the default in one place —
     * {@code app.retrieval.default-top-k}, applied by {@code RetrievalService} — and
     * distinguishes a value the caller chose from one it left unset.
     */
    public Integer requestedTopK() {
        return topK;
    }
}
