package com.example.hrbot.rag;

import com.example.hrbot.dto.ChatRequest;
import com.example.hrbot.dto.ChatResponse;
import com.example.hrbot.dto.CitedAnswer;
import com.example.hrbot.dto.RetrievedChunk;
import com.example.hrbot.prompts.PromptBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
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

    private final RetrievalService retrievalService;
    private final PromptBuilder promptBuilder;
    private final ChatClient chatClient;
    private final String citationMode;

    public ChatService(
            RetrievalService retrievalService,
            PromptBuilder promptBuilder,
            ChatClient chatClient,
            @Value("${app.citations.mode:" + MODE_MODEL_ATTRIBUTED + "}") String citationMode) {

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
     * The prompt {@link #chat} would send for these documents and this question.
     *
     * <p>Exposed so that diagnostic callers render the prompt belonging to the
     * configured mode without selecting a template themselves.
     *
     * <p>Under {@link #MODE_MODEL_ATTRIBUTED} the structured-output converter appends a
     * schema instruction to this text when the call is made; that suffix is reported
     * separately by {@link #structuredOutputSchema()} rather than spliced in here.
     */
    public String promptFor(List<Document> documents, String question) {
        return modelAttributed()
                ? promptBuilder.buildCitedPrompt(documents, question)
                : promptBuilder.buildPrompt(documents, question);
    }

    /** The template backing the active citation mode, without context. */
    public String activePromptTemplate() {
        return modelAttributed()
                ? promptBuilder.getCitedTemplatePreview()
                : promptBuilder.getTemplatePreview();
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
        String prompt = promptBuilder.buildCitedPrompt(documents, question);

        log.info("Step 3: Generating structured answer");
        CitedAnswer cited;
        try {
            cited = chatClient.prompt()
                    .user(prompt)
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

        List<RetrievedChunk> attributed = resolveCitedChunks(cited.citations(), chunks);

        if (attributed.isEmpty()) {
            // Refusals cite nothing by design. Also reached when no reported label
            // resolves, where citing the pages searched remains accurate.
            log.info("Step 3 complete: {} character answer, no verified citations",
                    cited.answer().length());
            return new GeneratedAnswer(cited.answer().trim(), buildCitations(chunks));
        }

        log.info("Step 3 complete: {} character answer, {} of {} sources cited",
                cited.answer().length(), attributed.size(), chunks.size());
        return new GeneratedAnswer(cited.answer().trim(), buildCitations(attributed));
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

    /** Generates a plain answer and cites every retrieved page. */
    private GeneratedAnswer generatePlain(List<Document> documents,
                                          String question,
                                          List<RetrievedChunk> chunks) {
        log.info("Step 2: Building grounded prompt with context");
        String prompt = promptBuilder.buildPrompt(documents, question);

        log.info("Step 3: Generating answer");
        String answer = chatClient.prompt()
                .user(prompt)
                .call()
                .content();

        // content() is null when the response carries no generation, e.g. if the model
        // returned only tool calls or the request was truncated.
        if (answer == null || answer.isBlank()) {
            throw new IllegalStateException(
                    "The language model returned an empty response. "
                            + "Check that the configured model is available and that the prompt "
                            + "fits within its context window.");
        }

        log.info("Step 3 complete: {} character answer", answer.length());
        return new GeneratedAnswer(answer.trim(), buildCitations(chunks));
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

