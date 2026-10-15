package com.example.hrbot.rag;

import com.example.hrbot.dto.ChatRequest;
import com.example.hrbot.dto.ChatResponse;
import com.example.hrbot.dto.ChatStreamEvent;
import com.example.hrbot.dto.CitedAnswer;
import com.example.hrbot.dto.RetrievedChunk;
import com.example.hrbot.dto.SourceAttribution;
import com.example.hrbot.prompts.PromptBuilder;
import com.example.hrbot.prompts.PromptBuilder.GroundedPrompt;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * Orchestrates the retrieval-augmented generation pipeline for a question:
 * <pre>
 *   retrieve  {@link RetrievalService}  question -> embedding -> nearest chunks
 *   augment   {@link PromptBuilder}     chunks + question -> grounded prompt
 *   generate  {@link ChatClient}        prompt -> answer
 * </pre>
 *
 * <p>Citations are produced by one of two strategies, selected by
 * {@code app.citations.mode}; see {@link #MODE_MODEL_ATTRIBUTED} and
 * {@link #MODE_RETRIEVAL_SCOPED}. The former degrades to the latter automatically, so
 * the choice affects citation precision but never whether an answer is returned.
 */
@Slf4j
@Service
public class ChatService {

    /**
     * Citation strategy: the model reports which labelled sources it used, and this class
     * verifies every label against the set it supplied.
     */
    static final String MODE_MODEL_ATTRIBUTED = "model-attributed";

    /** Citation strategy: cite every page that was retrieved, without attribution. */
    static final String MODE_RETRIEVAL_SCOPED = "retrieval-scoped";

    /**
     * The schema instruction Spring AI's structured-output converter appends to a prompt
     * when a reply is requested as {@link CitedAnswer}. Derived from the record itself,
     * so it always describes the shape the code expects.
     */
    private static final String CITED_ANSWER_SCHEMA =
            new BeanOutputConverter<>(CitedAnswer.class).getFormat();

    /**
     * Parses the streaming endpoint's attribution reply, and supplies the schema
     * instruction appended to that prompt.
     */
    private static final BeanOutputConverter<SourceAttribution> ATTRIBUTION_CONVERTER =
            new BeanOutputConverter<>(SourceAttribution.class);

    private final RetrievalService retrievalService;
    private final PromptBuilder promptBuilder;
    private final ChatClient chatClient;
    private final String citationMode;
    private final Duration streamTimeout;

    public ChatService(
            RetrievalService retrievalService,
            PromptBuilder promptBuilder,
            ChatClient chatClient,
            @Value("${app.citations.mode:" + MODE_MODEL_ATTRIBUTED + "}") String citationMode,
            @Value("${app.streaming.timeout:150s}") Duration streamTimeout) {

        if (!MODE_MODEL_ATTRIBUTED.equals(citationMode)
                && !MODE_RETRIEVAL_SCOPED.equals(citationMode)) {
            throw new IllegalArgumentException(
                    "Unknown app.citations.mode '" + citationMode + "'. Expected '"
                            + MODE_MODEL_ATTRIBUTED + "' or '" + MODE_RETRIEVAL_SCOPED + "'.");
        }

        this.retrievalService = retrievalService;
        this.promptBuilder = promptBuilder;
        this.chatClient = chatClient;
        this.citationMode = citationMode;
        this.streamTimeout = streamTimeout;

        log.info("ChatService initialised with citation mode '{}'", citationMode);
    }

    /**
     * Executes the complete pipeline for a question.
     *
     * @param request the question and any optional overrides
     * @return the answer with its citations and retrieval metadata
     */
    public ChatResponse chat(ChatRequest request) {
        long startTime = System.currentTimeMillis();
        String question = request.question();
        // A null topK means unspecified; RetrievalService applies the configured default.
        Integer topK = request.requestedTopK();

        log.info("RAG pipeline started: question='{}', topK={}",
                question, topK == null ? "default" : topK);

        log.info("Step 1: Retrieving relevant chunks from vector store...");
        List<Document> retrievedDocs = retrievalService.retrieveDocuments(question, topK);
        log.info("Step 1 complete: {} chunks retrieved", retrievedDocs.size());

        for (int i = 0; i < retrievedDocs.size(); i++) {
            Document doc = retrievedDocs.get(i);
            log.debug("  Chunk {}: score={}, source={}, length={}",
                    i + 1,
                    doc.getScore(),
                    doc.getMetadata().getOrDefault("documentName", "unknown"),
                    doc.getText().length());
        }

        List<RetrievedChunk> chunks = toRetrievedChunks(retrievedDocs);

        GeneratedAnswer generated = modelAttributed()
                ? generateWithAttribution(retrievedDocs, question, chunks)
                : generatePlain(retrievedDocs, question, chunks);

        long processingTime = System.currentTimeMillis() - startTime;

        String answer = generated.answer();
        List<String> sources = generated.sources();

        log.info("RAG pipeline completed in {}ms", processingTime);

        return new ChatResponse(
                answer,
                question,
                chunks.size(),
                sources,
                chunks,
                processingTime
        );
    }

    /** An answer plus the citations that will be shown with it. */
    private record GeneratedAnswer(String answer, List<String> sources) {}

    /**
     * Executes the pipeline for a question and streams the answer as it is generated.
     *
     * <p>Retrieval runs before this method returns, so a failure there — the database or
     * the embedding model being unavailable — surfaces as an ordinary exception and an
     * HTTP error status. Once the stream has started the status has been sent, so a
     * failure during generation becomes a terminal {@link ChatStreamEvent.Error} instead.
     *
     * <p>The answer is generated from the plain prompt and streamed as it arrives;
     * structured output cannot be streamed as readable text. Citations follow
     * {@code app.citations.mode}: under {@link #MODE_MODEL_ATTRIBUTED}, a second call asks
     * which labelled sources the completed answer was taken from and every reported label
     * is verified, falling back to every retrieved page when none survives; under
     * {@link #MODE_RETRIEVAL_SCOPED}, every retrieved page is cited without that call.
     * A refusal cites nothing in either mode.
     *
     * <p>Both model calls are non-blocking, so a client disconnect cancels whichever is
     * running and closes its connection to the model.
     *
     * @param request the question and any optional overrides
     * @return retrieval, then tokens, then exactly one of done or error
     */
    public Flux<ChatStreamEvent> streamChat(ChatRequest request) {
        long startTime = System.currentTimeMillis();
        String requestId = UUID.randomUUID().toString().substring(0, 8);
        String question = request.question();
        Integer topK = request.requestedTopK();

        log.info("[{}] Streaming RAG pipeline started: question='{}', topK={}",
                requestId, question, topK == null ? "default" : topK);

        List<Document> retrievedDocs = retrievalService.retrieveDocuments(question, topK);
        List<RetrievedChunk> chunks = toRetrievedChunks(retrievedDocs);
        log.info("[{}] {} chunks retrieved", requestId, chunks.size());

        GroundedPrompt prompt = promptBuilder.buildPrompt(retrievedDocs, question);

        // Deferred so that the accumulated answer belongs to one subscription.
        Flux<ChatStreamEvent> generation = Flux.defer(() -> {
            StringBuilder answer = new StringBuilder();

            Flux<ChatStreamEvent> tokens = chatClient.prompt()
                    .system(prompt.system())
                    .user(prompt.user())
                    .stream()
                    .content()
                    .filter(text -> !text.isEmpty())
                    .doOnNext(text -> {
                        if (answer.isEmpty()) {
                            log.info("[{}] First token after {}ms",
                                    requestId, System.currentTimeMillis() - startTime);
                        }
                        answer.append(text);
                    })
                    .map(ChatStreamEvent.Token::new);

            // Deferred until the last token has arrived and the whole answer is known.
            Mono<ChatStreamEvent> done = Mono.defer(() -> completeStream(
                    requestId, answer.toString(), retrievedDocs, chunks, startTime));

            return tokens.concatWith(done);
        });

        Instant deadline = Instant.now().plus(streamTimeout);

        return Flux.concat(
                        Flux.just(new ChatStreamEvent.Retrieval(
                                requestId, question, chunks.size(), chunks)),
                        generation)
                // One deadline for the whole stream, enforced here so that an overrun ends
                // with an error event. The servlet container's async timeout would instead
                // drop the connection with no terminal event, so it is set longer.
                .timeout(Mono.delay(streamTimeout), event -> Mono.delay(timeLeft(deadline)))
                .onErrorResume(e -> {
                    if (e instanceof TimeoutException) {
                        log.error("[{}] Streaming answer exceeded the {} limit",
                                requestId, streamTimeout);
                    } else {
                        log.error("[{}] Streaming answer failed after {}ms: {}",
                                requestId, System.currentTimeMillis() - startTime, e.getMessage(), e);
                    }
                    return Flux.just(new ChatStreamEvent.Error(requestId,
                            "The answer could not be completed. Quote reference " + requestId
                                    + " when reporting this; the application log holds the detail."));
                })
                .doOnCancel(() -> log.info("[{}] Client disconnected after {}ms; generation cancelled",
                        requestId, System.currentTimeMillis() - startTime));
    }

    /** Time remaining until the deadline, or zero once it has passed. */
    private static Duration timeLeft(Instant deadline) {
        Duration left = Duration.between(Instant.now(), deadline);
        return left.isNegative() ? Duration.ZERO : left;
    }

    /**
     * Decides the citations for a completed streamed answer.
     *
     * <p>Signals {@link IllegalStateException} if the model produced no text, which the
     * stream reports as an error event.
     */
    private Mono<ChatStreamEvent> completeStream(String requestId,
                                                 String rawAnswer,
                                                 List<Document> documents,
                                                 List<RetrievedChunk> chunks,
                                                 long startTime) {
        String answer = rawAnswer.trim();
        if (answer.isEmpty()) {
            return Mono.error(emptyResponse());
        }

        if (isRefusal(answer)) {
            log.info("[{}] Stream completed in {}ms: refusal, cited nothing",
                    requestId, System.currentTimeMillis() - startTime);
            return Mono.just(new ChatStreamEvent.Done(List.of(), System.currentTimeMillis() - startTime));
        }

        if (!modelAttributed()) {
            log.info("[{}] Stream completed in {}ms: {} character answer, citing every retrieved page",
                    requestId, System.currentTimeMillis() - startTime, answer.length());
            return Mono.just(new ChatStreamEvent.Done(buildCitations(chunks), System.currentTimeMillis() - startTime));
        }

        return attribute(requestId, answer, documents, chunks).map(attributed -> {
            long processingTime = System.currentTimeMillis() - startTime;

            if (attributed.isEmpty()) {
                // The answer has already been shown. Citing every page that was searched is
                // less precise but still accurate.
                log.info("[{}] Stream completed in {}ms: {} character answer, no verified sources",
                        requestId, processingTime, answer.length());
                return new ChatStreamEvent.Done(buildCitations(chunks), processingTime);
            }

            log.info("[{}] Stream completed in {}ms: {} character answer, {} of {} sources cited",
                    requestId, processingTime, answer.length(), attributed.size(), chunks.size());
            return new ChatStreamEvent.Done(buildCitations(attributed), processingTime);
        });
    }

    /**
     * Asks the model which labelled sources a completed answer was taken from, and keeps
     * only the labels that were supplied.
     *
     * <p>The reply is streamed and assembled rather than requested with a blocking call,
     * so that a client disconnect during attribution closes the connection to the model
     * as it does during generation.
     *
     * <p>Emits an empty list rather than an error when the reply cannot be obtained or
     * parsed: the answer has already reached the client, so the caller falls back to
     * citing every retrieved page instead of discarding it.
     */
    private Mono<List<RetrievedChunk>> attribute(String requestId,
                                                 String answer,
                                                 List<Document> documents,
                                                 List<RetrievedChunk> chunks) {
        long attributionStart = System.currentTimeMillis();
        GroundedPrompt prompt = promptBuilder.buildAttributionPrompt(documents, answer);

        return chatClient.prompt()
                .system(prompt.system())
                .user(prompt.user() + System.lineSeparator() + ATTRIBUTION_CONVERTER.getFormat())
                .stream()
                .content()
                .collect(Collectors.joining())
                .map(reply -> {
                    SourceAttribution attribution = ATTRIBUTION_CONVERTER.convert(reply);
                    List<String> labels = attribution == null ? List.of() : attribution.citations();
                    List<RetrievedChunk> resolved = resolveCitedChunks(labels, chunks);
                    log.info("[{}] Attribution took {}ms: model reported {}, {} verified",
                            requestId, System.currentTimeMillis() - attributionStart,
                            labels, resolved.size());
                    return resolved;
                })
                .onErrorResume(e -> {
                    log.warn("[{}] Could not attribute the answer ({}); citing every retrieved page",
                            requestId, e.getMessage());
                    return Mono.just(List.of());
                });
    }

    /**
     * The prompt {@link #chat} would send for these documents and this question, both
     * messages of it.
     *
     * <p>Exposed so that diagnostic callers render the prompt belonging to the
     * configured mode without selecting a template themselves.
     *
     * <p>Under {@link #MODE_MODEL_ATTRIBUTED} the structured-output converter appends a
     * schema instruction to the user message when the call is made; that suffix is
     * reported separately by {@link #structuredOutputSchema()} rather than spliced in
     * here.
     */
    public GroundedPrompt promptFor(List<Document> documents, String question) {
        return modelAttributed()
                ? promptBuilder.buildCitedPrompt(documents, question)
                : promptBuilder.buildPrompt(documents, question);
    }

    /** The system message backing the active citation mode. */
    public String activeSystemPrompt() {
        return modelAttributed()
                ? promptBuilder.getCitedSystemPrompt()
                : promptBuilder.getSystemPrompt();
    }

    /** The user template backing the active citation mode, without context. */
    public String activeUserTemplate() {
        return modelAttributed()
                ? promptBuilder.getCitedUserTemplate()
                : promptBuilder.getUserTemplate();
    }

    /** The configured citation mode. */
    public String citationMode() {
        return citationMode;
    }

    /**
     * The schema instruction appended to the prompt under
     * {@link #MODE_MODEL_ATTRIBUTED}, or {@code null} when the active mode does not
     * request structured output.
     */
    public String structuredOutputSchema() {
        return modelAttributed() ? CITED_ANSWER_SCHEMA : null;
    }

    private boolean modelAttributed() {
        return MODE_MODEL_ATTRIBUTED.equals(citationMode);
    }

    /**
     * Generates an answer and asks the model which of the labelled sources it used, then
     * validates every label it reports.
     *
     * <p>The model is asked because only it knows which source a claim came from:
     * answers paraphrase, so matching answer text back against chunk text fails on
     * exactly the answers worth citing. Its report is a proposal, not the last word —
     * the label set is closed and small, so anything outside it is discarded, and if no
     * label survives, citations fall back to every retrieved page.
     */
    private GeneratedAnswer generateWithAttribution(List<Document> documents,
                                                    String question,
                                                    List<RetrievedChunk> chunks) {
        log.info("Step 2: Building labelled prompt for model-attributed citations");
        GroundedPrompt prompt = promptBuilder.buildCitedPrompt(documents, question);

        log.info("Step 3: Generating structured answer");
        CitedAnswer cited;
        try {
            cited = chatClient.prompt()
                    .system(prompt.system())
                    .user(prompt.user())
                    .call()
                    .entity(CitedAnswer.class);
        } catch (RuntimeException e) {
            log.warn("Could not obtain a structured answer ({}); retrying without attribution",
                    e.getMessage());
            return generatePlain(documents, question, chunks);
        }

        if (cited == null || cited.answer() == null || cited.answer().isBlank()) {
            log.warn("Structured answer was empty; retrying without attribution");
            return generatePlain(documents, question, chunks);
        }

        String answer = cited.answer().trim();

        // A refusal states that the retrieved text does not answer the question, so
        // there is nothing to attribute. Citing the pages searched would present the
        // refusal as though it had been drawn from them.
        if (isRefusal(answer)) {
            log.info("Step 3 complete: refusal, cited nothing");
            return new GeneratedAnswer(answer, List.of());
        }

        List<RetrievedChunk> attributed = resolveCitedChunks(cited.citations(), chunks);

        if (attributed.isEmpty()) {
            // No reported label resolved. The answer stands, and citing every page that
            // was searched is less precise but still accurate.
            log.info("Step 3 complete: {} character answer, no verified citations",
                    answer.length());
            return new GeneratedAnswer(answer, buildCitations(chunks));
        }

        log.info("Step 3 complete: {} character answer, {} of {} sources cited",
                answer.length(), attributed.size(), chunks.size());
        return new GeneratedAnswer(answer, buildCitations(attributed));
    }

    /**
     * The first sentence of the refusal, used as the marker that an answer is a refusal.
     *
     * <p>Anchoring on the opening sentence rather than the whole of
     * {@link PromptBuilder#REFUSAL_SENTENCE} tolerates a model that drops or rewords the
     * closing "contact HR" clause while still refusing.
     */
    private static final String REFUSAL_OPENING = PromptBuilder.REFUSAL_SENTENCE
            .substring(0, PromptBuilder.REFUSAL_SENTENCE.indexOf('.') + 1);

    /**
     * Whether an answer declines to answer from the retrieved text.
     *
     * <p>Matched on the opening of the refusal rather than by containment. Containment
     * would also match an answer that resolves part of a multi-part question and appends
     * the refusal for the rest — that answer carries real policy facts and must keep its
     * citations. Anchoring at the start distinguishes "this is a refusal" from "this ends
     * in a refusal".
     *
     * <p>Whitespace is collapsed first, so a model that reflows the sentence across lines
     * is still recognised.
     */
    private boolean isRefusal(String answer) {
        return collapseWhitespace(answer).startsWith(REFUSAL_OPENING);
    }

    /** Reduces every run of whitespace to a single space, so wrapping cannot matter. */
    private String collapseWhitespace(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }

    /**
     * Maps the labels the model reported back to retrieved chunks, discarding any that
     * were not supplied to it.
     *
     * <p>Each entry is split on runs of non-alphanumeric characters and every fragment is
     * resolved separately, so {@code "[s1]"}, {@code "S1."} and a single entry holding
     * {@code "S1, S2"} all resolve. Tolerating that variation does not widen the set of
     * accepted values: a fragment still has to name a source that was supplied.
     */
    private List<RetrievedChunk> resolveCitedChunks(List<String> reportedLabels,
                                                    List<RetrievedChunk> chunks) {
        Map<String, RetrievedChunk> byLabel = new LinkedHashMap<>();
        for (int i = 0; i < chunks.size(); i++) {
            byLabel.put(PromptBuilder.sourceLabel(i), chunks.get(i));
        }

        List<RetrievedChunk> resolved = new ArrayList<>();
        List<String> rejected = new ArrayList<>();

        for (String reported : reportedLabels) {
            if (reported == null || reported.isBlank()) {
                rejected.add(reported);
                continue;
            }
            for (String fragment : reported.split("[^A-Za-z0-9]+")) {
                if (fragment.isEmpty()) {
                    continue;
                }
                RetrievedChunk chunk = byLabel.get(normaliseLabel(fragment));
                if (chunk == null) {
                    rejected.add(fragment);
                } else if (!resolved.contains(chunk)) {
                    resolved.add(chunk);
                }
            }
        }

        if (!rejected.isEmpty()) {
            log.warn("Discarded {} citation label(s) that were not supplied to the model: {}",
                    rejected.size(), rejected);
        }
        return resolved;
    }

    /** Uppercases and strips anything that is not a letter or digit. */
    private String normaliseLabel(String label) {
        return label.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
    }

    /** Generates a plain answer and cites every retrieved page, unless it is a refusal. */
    private GeneratedAnswer generatePlain(List<Document> documents,
                                          String question,
                                          List<RetrievedChunk> chunks) {
        log.info("Step 2: Building grounded prompt with context");
        GroundedPrompt prompt = promptBuilder.buildPrompt(documents, question);

        log.info("Step 3: Generating answer");
        String answer = chatClient.prompt()
                .system(prompt.system())
                .user(prompt.user())
                .call()
                .content();

        // content() is null when the response carries no generation, e.g. if the model
        // returned only tool calls or the request was truncated.
        if (answer == null || answer.isBlank()) {
            throw emptyResponse();
        }

        String trimmed = answer.trim();

        if (isRefusal(trimmed)) {
            log.info("Step 3 complete: refusal, cited nothing");
            return new GeneratedAnswer(trimmed, List.of());
        }

        log.info("Step 3 complete: {} character answer", trimmed.length());
        return new GeneratedAnswer(trimmed, buildCitations(chunks));
    }

    /** The failure raised when the model generates no text, on either endpoint. */
    private static IllegalStateException emptyResponse() {
        return new IllegalStateException(
                "The language model returned an empty response. "
                        + "Check that the configured model is available and that the prompt "
                        + "fits within its context window.");
    }

    /** Converts Spring AI documents into the response DTO, preserving retrieval order. */
    private List<RetrievedChunk> toRetrievedChunks(List<Document> documents) {
        return documents.stream()
                .map(document -> {
                    Map<String, Object> metadata = document.getMetadata();
                    return new RetrievedChunk(
                            document.getText(),
                            document.getScore() != null ? document.getScore() : 0.0,
                            metadata.getOrDefault("documentName", "unknown").toString(),
                            metadata.get("chunkIndex") instanceof Number index ? index.intValue() : -1,
                            metadata
                    );
                })
                .toList();
    }

    /**
     * Derives user-facing citations from retrieval metadata.
     *
     * <p>Produces one entry per source document, listing the pages that were retrieved:
     * <pre>
     *   Leave Policy.pdf (pages 1, 3)
     *   OverTime Policy.pdf (page 2)
     * </pre>
     *
     * <p>Page numbers come from retrieval metadata rather than from the model, so the
     * mapping from chunk to page is a lookup and cannot be misreported. The claim is
     * that these pages were supplied, not that each one influenced the answer;
     * per-sentence attribution would need a separate verification pass.
     *
     * @param chunks retrieved chunks, in relevance order
     * @return citation strings, ordered by first appearance in the results
     */
    private List<String> buildCitations(List<RetrievedChunk> chunks) {
        Map<String, SortedSet<Integer>> pagesByDocument = new LinkedHashMap<>();

        for (RetrievedChunk chunk : chunks) {
            SortedSet<Integer> pages = pagesByDocument
                    .computeIfAbsent(chunk.documentName(), name -> new TreeSet<>());

            // Record every page the chunk covers. A chunk spanning a page break contains
            // text from both, so citing only its start page would misattribute the rest.
            if (chunk.pageNumber() > 0) {
                for (int page = chunk.pageNumber(); page <= chunk.pageNumberEnd(); page++) {
                    pages.add(page);
                }
            }
        }

        return pagesByDocument.entrySet().stream()
                .map(entry -> formatCitation(entry.getKey(), entry.getValue()))
                .toList();
    }

    private String formatCitation(String documentName, SortedSet<Integer> pages) {
        if (pages.isEmpty()) {
            // No page metadata available; cite the document alone rather than guess.
            return documentName;
        }
        String label = pages.size() == 1 ? "page" : "pages";
        String pageList = pages.stream().map(String::valueOf).collect(Collectors.joining(", "));
        return "%s (%s %s)".formatted(documentName, label, pageList);
    }
}

