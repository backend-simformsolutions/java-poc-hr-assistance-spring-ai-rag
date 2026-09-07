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
 * <p>Both templates follow the same order — role and rules, then retrieved context,
 * then the question. The question comes last so the model reads the source material
 * before it starts answering.
 *
 * <p>Perfect retrieval still permits a fabricated answer if the prompt does not
 * constrain the model, so each template instructs it to use only the supplied text and
 * gives it a fixed sentence to return when the answer is absent — making "I don't know"
 * an easy path rather than an improvised one. Sampling temperature, the other half of
 * that defence, is configured on the model.
 *
 * <p>Rule lists are short. Instructions compete for a model's attention and smaller
 * models honour only a few hard constraints reliably, so each rule has to earn its
 * place. Anything deterministic belongs in application code — page numbers, for
 * instance, which {@code ChatService} resolves from retrieval metadata.
 */
@Slf4j
@Service
public class PromptBuilder {

    /**
     * The exact wording returned when the retrieved text does not answer the question.
     *
     * <p>Both templates instruct the model to reproduce this sentence verbatim, so it is
     * defined once: a refusal reads the same whichever citation mode produced it.
     */
    static final String REFUSAL_SENTENCE =
            "I could not find this information in the HR policies provided. "
                    + "Please contact the HR department directly.";

    private static final String PROMPT_TEMPLATE = """
            You are an HR Policy Assistant. Answer the employee's question using ONLY the HR policy documents provided below.

            RULES:
            1. Use ONLY information explicitly stated in the documents below. Do NOT use outside
               knowledge, and do NOT infer or assume anything that is not written there.
            2. If the information is NOT in the documents, respond EXACTLY:
               "{refusal}"
            3. When the text contains a table or a numbered list, include ALL rows/items — do not
               stop at the first one.
            4. For numbers and quantities (days, percentages, hours), quote the EXACT value from the text.
            5. Do NOT name or refer to the policy documents anywhere in your answer, and do NOT
               write a "Source", "Citation" or "Answer comes from" section. The application lists
               the source documents and page numbers automatically. State the policy facts only.

            --------------- HR POLICY DOCUMENTS ---------------
            %s
            --------------- END OF DOCUMENTS ---------------

            Employee Question: %s

            Answer:
            """.replace("{refusal}", REFUSAL_SENTENCE);

    /**
     * Prompt used when citations are model-attributed. Each source carries a short label
     * that the model is asked to reference, and the reply is requested as structured data
     * rather than prose.
     *
     * <p>Labels are terse ({@code S1}, {@code S2}) rather than filenames or chunk
     * identifiers: a short token drawn from a small, closed set is easy for a model to
     * copy accurately, and any value outside that set is rejected by the caller.
     *
     * <p>No "Answer:" trailer here: the structured-output converter appends the expected
     * JSON schema to the end of this prompt.
     */
    private static final String CITED_PROMPT_TEMPLATE = """
            You are an HR Policy Assistant. Answer the employee's question using ONLY the numbered sources below.

            RULES:
            1. Use ONLY information explicitly stated in the sources below. Do NOT use outside
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
               example ["S1"] or ["S2","S4"]. Use only labels that appear below. Do NOT invent labels.
            7. Do NOT mention source labels, document names or file names inside "answer".

            --------------- SOURCES ---------------
            %s
            --------------- END OF SOURCES ---------------

            Employee Question: %s
            """.replace("{refusal}", REFUSAL_SENTENCE);

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
     * Builds a prompt whose sources are individually labelled, for use with
     * {@link com.example.hrbot.dto.CitedAnswer}.
     *
     * @param documents retrieved documents, in relevance order
     * @param question  the user's question
     * @return the prompt to send to the model
     */
    public String buildCitedPrompt(List<Document> documents, String question) {
        String sources = formatLabelledSources(documents);
        String prompt = CITED_PROMPT_TEMPLATE.formatted(sources, question);

        log.debug("Built cited prompt of {} characters from {} labelled sources",
                prompt.length(), documents.size());
        return prompt;
    }

    /**
     * Renders each document as a labelled block. The document name is included on the
     * label line so the model can still answer "which policy says this", but it sits
     * outside the text body where it is less likely to be copied into the answer.
     */
    private String formatLabelledSources(List<Document> documents) {
        if (documents.isEmpty()) {
            return "[No relevant HR policy documents were found for this question.]";
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
     * Builds a complete prompt from retrieved context and the user's question.
     *
     * @param documents retrieved documents, in relevance order
     * @param question  the user's question
     * @return the prompt to send to the model
     */
    public String buildPrompt(List<Document> documents, String question) {
        String context = formatContext(documents);
        String prompt = PROMPT_TEMPLATE.formatted(context, question);

        log.debug("Built prompt of {} characters from {} context chunks", prompt.length(), documents.size());
        return prompt;
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
            return "[No relevant HR policy documents were found for this question.]";
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

    /** The plain template, without context, for diagnostic endpoints. */
    public String getTemplatePreview() {
        return PROMPT_TEMPLATE;
    }

    /** The labelled-source template, without context, for diagnostic endpoints. */
    public String getCitedTemplatePreview() {
        return CITED_PROMPT_TEMPLATE;
    }
}
