package com.example.hrbot.dto;

import java.util.List;

/**
 * One event of a streamed chat answer.
 *
 * <p>A stream is always {@link Retrieval}, then zero or more {@link Token}s, then exactly
 * one of {@link Done} or {@link Error}. The terminal event is what tells a client the
 * answer is complete rather than cut off.
 *
 * <p>Transport-neutral on purpose: the service produces these, and the controller decides
 * how they are framed on the wire.
 */
public sealed interface ChatStreamEvent {

    /**
     * What was retrieved for the question, sent before generation starts.
     *
     * @param requestId       identifies this request in the log and in any error event
     * @param question        the question as asked
     * @param chunksRetrieved number of chunks retrieved for this question
     * @param retrievedChunks the chunks supplied to the model, in relevance order
     */
    record Retrieval(
            String requestId,
            String question,
            int chunksRetrieved,
            List<RetrievedChunk> retrievedChunks
    ) implements ChatStreamEvent {}

    /**
     * The next piece of the answer, to be appended to what has arrived so far.
     *
     * @param text the generated text, including any leading whitespace
     */
    record Token(String text) implements ChatStreamEvent {}

    /**
     * The answer is complete and its sources have been decided.
     *
     * @param sources          user-facing citations, one entry per document with its pages,
     *                         in the same format as {@link ChatResponse#sources()}
     * @param processingTimeMs wall-clock time for retrieval, generation and attribution
     */
    record Done(
            List<String> sources,
            long processingTimeMs
    ) implements ChatStreamEvent {}

    /**
     * Generation failed after the stream had started. Text already received is incomplete.
     *
     * @param requestId the reference under which the failure is logged
     * @param message   what the client should be told; carries no internal detail
     */
    record Error(String requestId, String message) implements ChatStreamEvent {}
}
