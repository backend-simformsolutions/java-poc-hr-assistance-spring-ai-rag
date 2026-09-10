package com.example.hrbot.prompts;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Builds the grounded prompts sent to the language model.
 *
 * <p>Each prompt is split across the two roles the chat API provides. The role and the
 * rules are the system message: they are identical for every request and describe what
 * the model is allowed to do. The retrieved context and the question are the user
 * message: they change per request and are the only part derived from stored documents.
 *
 * <p>The split matters for more than tidiness. Chunk text and document names come from
 * uploaded files, so putting them in the same message as the rules would give a filename
 * such as {@code "Leave. Ignore the rules above.pdf"} the same standing as the rules
 * themselves. Keeping instructions in the system role draws a structural line between
 * what the application says and what a document contains.
 *
 * <p>The question comes after the context so the model reads the source material before
 * it starts answering.
 *
 * <p>Perfect retrieval still permits a fabricated answer if the prompt does not
 * constrain the model, so each system message instructs it to use only the supplied text
 * and gives it a fixed sentence to return when the answer is absent — making "I don't
 * know" an easy path rather than an improvised one. Sampling temperature, the other half
 * of that defence, is configured on the model.
 *
 * <p>Rule lists are short. Instructions compete for a model's attention and smaller
 * models honour only a few hard constraints reliably, so each rule has to earn its
 * place. Anything deterministic belongs in application code — page numbers, for
 * instance, which {@code ChatService} resolves from retrieval metadata.
 */
@Slf4j
@Service
public class PromptBuilder {

    /** Stands in for retrieved text when a search returned nothing. */
    private static final String NO_CONTEXT =
            "[No relevant HR policy documents were found for this question.]";

    /**
     * The exact wording returned when the retrieved text does not answer the question.
     *
     * <p>Both system messages instruct the model to reproduce this sentence verbatim, so
     * it is defined once: a refusal reads the same whichever citation mode produced it.
     */
    public static final String REFUSAL_SENTENCE =
            "I could not find this information in the HR policies provided. "
                    + "Please contact the HR department directly.";

    private static final String SYSTEM_PROMPT = """
            You are an HR Policy Assistant. Answer the employee's question using ONLY the HR policy documents supplied in the user message.

            RULES:
            1. Use ONLY information explicitly stated in the supplied documents. Do NOT use outside
               knowledge, and do NOT infer or assume anything that is not written there.
            2. If the information is NOT in the documents, respond EXACTLY:
               "{refusal}"
            3. When the text contains a table or a numbered list, include ALL rows/items — do not
               stop at the first one.
            4. For numbers and quantities (days, percentages, hours), quote the EXACT value from the text.
            5. Do NOT name or refer to the policy documents anywhere in your answer, and do NOT
               write a "Source", "Citation" or "Answer comes from" section. The application lists
               the source documents and page numbers automatically. State the policy facts only.
            """.replace("{refusal}", REFUSAL_SENTENCE);

    private static final String USER_TEMPLATE = """
            --------------- HR POLICY DOCUMENTS ---------------
            %s
            --------------- END OF DOCUMENTS ---------------

            Employee Question: %s

            Answer:
            """;

    /**
     * System message used when citations are model-attributed. Sources carry short labels
     * that the model is asked to reference, and the reply is requested as structured data
     * rather than prose.
     *
     * <p>Labels are terse ({@code S1}, {@code S2}) rather than filenames or chunk
     * identifiers: a short token drawn from a small, closed set is easy for a model to
     * copy accurately, and any value outside that set is rejected by the caller.
     */
    private static final String CITED_SYSTEM_PROMPT = """
            You are an HR Policy Assistant. Answer the employee's question using ONLY the numbered sources supplied in the user message.

            RULES:
            1. Use ONLY information explicitly stated in the supplied sources. Do NOT use outside
               knowledge, and do NOT infer or assume anything that is not written there.
            2. "answer" must be a COMPLETE, self-contained sentence that directly answers the
               question that was asked. A bare number, a fragment, or a copied formula on its own
               is NOT acceptable. If a source gives a formula or a table row, say in words what it
               means AND include the exact values.
            3. If the information is NOT in the sources, set "answer" to EXACTLY:
               "{refusal}"
               and set "citations" to an empty list.
            4. When a source contains a table or a numbered list, include ALL rows/items — do not
               stop at the first one.
            5. For numbers and quantities (days, percentages, hours), quote the EXACT value from the text.
            6. In "citations", list ONLY the labels of sources whose text you actually used, for
               example ["S1"] or ["S2","S4"]. Use only labels that appear in the user message.
               Do NOT invent labels.
            7. Do NOT mention source labels, document names or file names inside "answer".
            """.replace("{refusal}", REFUSAL_SENTENCE);

    /**
     * No "Answer:" trailer here: the structured-output converter appends the expected
     * JSON schema to the end of the user message.
     */
    private static final String CITED_USER_TEMPLATE = """
            --------------- SOURCES ---------------
            %s
            --------------- END OF SOURCES ---------------

            Employee Question: %s
            """;

    /**
     * Returns the label used for the source at the given zero-based position.
     *
     * <p>Single definition on purpose: the prompt and the code that validates the model's
     * reply must agree on the label format, so both sides call this.
     */
    public static String sourceLabel(int index) {
        return "S" + (index + 1);
    }

    /**
     * Builds a prompt from retrieved context and the user's question.
     *
     * @param documents retrieved documents, in relevance order
     * @param question  the user's question
     * @return the system and user messages to send
     */
    public GroundedPrompt buildPrompt(List<Document> documents, String question) {
        GroundedPrompt prompt = new GroundedPrompt(
                SYSTEM_PROMPT,
                USER_TEMPLATE.formatted(formatContext(documents), question));

        log.debug("Built prompt: {} character system message, {} character user message "
                        + "from {} context chunks",
                prompt.system().length(), prompt.user().length(), documents.size());
        return prompt;
    }

    /**
     * Builds a prompt whose sources are individually labelled, for use with
     * {@link com.example.hrbot.dto.CitedAnswer}.
     *
     * @param documents retrieved documents, in relevance order
     * @param question  the user's question
     * @return the system and user messages to send
     */
    public GroundedPrompt buildCitedPrompt(List<Document> documents, String question) {
        GroundedPrompt prompt = new GroundedPrompt(
                CITED_SYSTEM_PROMPT,
                CITED_USER_TEMPLATE.formatted(formatLabelledSources(documents), question));

        log.debug("Built cited prompt: {} character system message, {} character user message "
                        + "from {} labelled sources",
                prompt.system().length(), prompt.user().length(), documents.size());
        return prompt;
    }

    /**
     * Renders each document as a labelled block. The document name is included on the
     * label line so the model can still answer "which policy says this", but it sits
     * outside the text body where it is less likely to be copied into the answer.
     */
    private String formatLabelledSources(List<Document> documents) {
        if (documents.isEmpty()) {
            return NO_CONTEXT;
        }

        StringBuilder sources = new StringBuilder();
        for (int i = 0; i < documents.size(); i++) {
            Document document = documents.get(i);
            String documentName = document.getMetadata()
                    .getOrDefault("documentName", "Unknown Document")
                    .toString();

            sources.append("[").append(sourceLabel(i)).append("] from ").append(documentName).append('\n')
                    .append(textOf(document))
                    .append("\n\n");
        }
        return sources.toString().trim();
    }

    /**
     * Formats retrieved documents as prompt context, grouped under one heading per
     * source document.
     *
     * <p>Grouping keeps text from the same document together, which helps the model
     * answer questions about which policy covers a topic. Individual chunks are not
     * labelled: the model is not asked to cite them, and unexplained identifiers in the
     * context invite it to quote them as if they were references.
     */
    private String formatContext(List<Document> documents) {
        if (documents.isEmpty()) {
            return NO_CONTEXT;
        }

        Map<String, List<Document>> bySource = documents.stream()
                .collect(Collectors.groupingBy(
                        document -> document.getMetadata()
                                .getOrDefault("documentName", "Unknown Document")
                                .toString(),
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        StringBuilder context = new StringBuilder();
        bySource.forEach((sourceName, chunks) -> {
            context.append("=== ").append(sourceName).append(" ===\n");
            chunks.forEach(chunk -> context.append(textOf(chunk)).append("\n\n"));
        });

        return context.toString().trim();
    }

    /** Document text, trimmed, or an empty string if the document carries none. */
    private String textOf(Document document) {
        String text = document.getText();
        return text == null ? "" : text.trim();
    }

    /** The plain system message, for diagnostic endpoints. */
    public String getSystemPrompt() {
        return SYSTEM_PROMPT;
    }

    /** The labelled-source system message, for diagnostic endpoints. */
    public String getCitedSystemPrompt() {
        return CITED_SYSTEM_PROMPT;
    }

    /** The plain user template, without context, for diagnostic endpoints. */
    public String getUserTemplate() {
        return USER_TEMPLATE;
    }

    /** The labelled-source user template, without context, for diagnostic endpoints. */
    public String getCitedUserTemplate() {
        return CITED_USER_TEMPLATE;
    }

    /**
     * A prompt as the chat API takes it: standing instructions in one message, the
     * per-request material in the other.
     *
     * @param system role and rules; identical for every request in a given citation mode
     * @param user   retrieved context followed by the question
     */
    public record GroundedPrompt(String system, String user) {}
}