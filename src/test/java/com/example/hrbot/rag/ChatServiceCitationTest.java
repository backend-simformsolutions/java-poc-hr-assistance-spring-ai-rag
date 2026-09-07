package com.example.hrbot.rag;

import com.example.hrbot.dto.ChatRequest;
import com.example.hrbot.dto.ChatResponse;
import com.example.hrbot.dto.CitedAnswer;
import com.example.hrbot.prompts.PromptBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies how citations are derived, which is the part of the pipeline that must be
 * correct by construction rather than by the model's cooperation.
 */
@DisplayName("ChatService citation handling")
class ChatServiceCitationTest {

    private RetrievalService retrievalService;
    private ChatClient chatClient;
    private ChatClient.ChatClientRequestSpec requestSpec;
    private ChatClient.CallResponseSpec responseSpec;

    /**
     * Three sources: two leave-policy chunks — the second spanning pages 2-3 — and one
     * overtime-policy chunk on page 1. The page-spanning chunk is what exercises the
     * range loop in {@code buildCitations}.
     */
    private static final List<Document> DOCUMENTS = List.of(
            document("Each team member will be entitled to 13 planned leaves each calendar year.",
                    "Leave Policy.pdf", 0, 1, 1),
            document("Upto 5 unplanned leaves will be encashed 1.5 times on Gross salary.",
                    "Leave Policy.pdf", 3, 2, 3),
            document("Team members can apply for a maximum of 15 hours per week.",
                    "OverTime Policy.pdf", 1, 1, 1)
    );

    private static Document document(String text, String name, int chunkIndex, int page, int pageEnd) {
        return new Document(text, Map.of(
                "documentName", name,
                "chunkIndex", chunkIndex,
                "pageNumber", page,
                "pageNumberEnd", pageEnd));
    }

    @BeforeEach
    void setUp() {
        retrievalService = mock(RetrievalService.class);
        chatClient = mock(ChatClient.class);
        requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        responseSpec = mock(ChatClient.CallResponseSpec.class);

        when(retrievalService.retrieveDocuments(anyString(), any())).thenReturn(DOCUMENTS);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
    }

    private ChatService service(String mode) {
        return new ChatService(retrievalService, new PromptBuilder(), chatClient, mode);
    }

    @Nested
    @DisplayName("citation mode configuration")
    class ModeConfiguration {

        /**
         * A misspelt mode must fail at startup. Taking the retrieval-scoped path instead
         * would leave an operator believing attribution was on while every answer cited
         * every retrieved page.
         */
        @Test
        void rejectsAnUnknownCitationMode() {
            assertThatThrownBy(() -> service("model_attributed"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unknown app.citations.mode")
                    .hasMessageContaining("model_attributed");
        }

        @Test
        void acceptsBothDocumentedModes() {
            assertThatCode(() -> service(ChatService.MODE_MODEL_ATTRIBUTED))
                    .doesNotThrowAnyException();
            assertThatCode(() -> service(ChatService.MODE_RETRIEVAL_SCOPED))
                    .doesNotThrowAnyException();
        }
    }

    private ChatResponse ask(String mode) {
        return service(mode).chat(new ChatRequest("How many planned leaves do I get?", null));
    }

    private void modelReturns(CitedAnswer answer) {
        when(responseSpec.entity(CitedAnswer.class)).thenReturn(answer);
    }

    @Nested
    @DisplayName("model-attributed mode")
    class ModelAttributed {

        @Test
        void citesOnlyThePageTheModelReported() {
            modelReturns(new CitedAnswer("You get 13 planned leaves each calendar year.", List.of("S1")));

            ChatResponse response = ask(ChatService.MODE_MODEL_ATTRIBUTED);

            // S1 is the first document: Leave Policy page 1. The other two are omitted.
            assertThat(response.sources()).containsExactly("Leave Policy.pdf (page 1)");
            assertThat(response.answer()).isEqualTo("You get 13 planned leaves each calendar year.");
        }

        @Test
        void groupsMultipleCitedSourcesByDocument() {
            modelReturns(new CitedAnswer("Leave and overtime detail.", List.of("S1", "S2", "S3")));

            ChatResponse response = ask(ChatService.MODE_MODEL_ATTRIBUTED);

            assertThat(response.sources()).containsExactly(
                    "Leave Policy.pdf (pages 1, 2, 3)",
                    "OverTime Policy.pdf (page 1)");
        }

        @Test
        void toleratesFormattingVariationInLabels() {
            modelReturns(new CitedAnswer("Answer text.", List.of("[s1]", "S3.")));

            ChatResponse response = ask(ChatService.MODE_MODEL_ATTRIBUTED);

            assertThat(response.sources()).containsExactly(
                    "Leave Policy.pdf (page 1)",
                    "OverTime Policy.pdf (page 1)");
        }

        /**
         * A model asked for a list sometimes returns one string holding several labels.
         * Each fragment resolves on its own, so the citation is not lost.
         */
        @Test
        void resolvesSeveralLabelsPackedIntoOneEntry() {
            modelReturns(new CitedAnswer("You get 13 planned leaves.", List.of("S1, S3")));

            ChatResponse response = ask(ChatService.MODE_MODEL_ATTRIBUTED);

            assertThat(response.sources())
                    .containsExactly("Leave Policy.pdf (page 1)", "OverTime Policy.pdf (page 1)");
        }

        /** A label outside the supplied set is provably wrong and must be discarded. */
        @Test
        void discardsInventedLabels() {
            modelReturns(new CitedAnswer("Answer text.", List.of("S1", "S9", "doc-42")));

            ChatResponse response = ask(ChatService.MODE_MODEL_ATTRIBUTED);

            assertThat(response.sources()).containsExactly("Leave Policy.pdf (page 1)");
        }

        @Test
        void deduplicatesRepeatedLabels() {
            modelReturns(new CitedAnswer("Answer text.", List.of("S1", "S1", "s1")));

            ChatResponse response = ask(ChatService.MODE_MODEL_ATTRIBUTED);

            assertThat(response.sources()).containsExactly("Leave Policy.pdf (page 1)");
        }

        /**
         * When nothing survives validation the answer is still returned; citations widen
         * to everything retrieved. Less precise, but never a false attribution.
         */
        @Test
        void fallsBackToAllRetrievedPagesWhenNoLabelSurvives() {
            modelReturns(new CitedAnswer("Answer text.", List.of("S7", "S8")));

            ChatResponse response = ask(ChatService.MODE_MODEL_ATTRIBUTED);

            assertThat(response.sources()).containsExactly(
                    "Leave Policy.pdf (pages 1, 2, 3)",
                    "OverTime Policy.pdf (page 1)");
        }

        /** A refusal legitimately cites nothing. */
        @Test
        void handlesEmptyCitationList() {
            modelReturns(new CitedAnswer(
                    "I could not find this information in the HR policies provided.", List.of()));

            ChatResponse response = ask(ChatService.MODE_MODEL_ATTRIBUTED);

            assertThat(response.answer()).startsWith("I could not find this information");
            assertThat(response.sources()).isNotEmpty();   // degrades to retrieved pages
        }

        @Test
        void handlesNullCitationList() {
            modelReturns(new CitedAnswer("Answer text.", null));

            ChatResponse response = ask(ChatService.MODE_MODEL_ATTRIBUTED);

            assertThat(response.answer()).isEqualTo("Answer text.");
        }

        /** A parse failure must not fail the request; it retries without attribution. */
        @Test
        void fallsBackWhenStructuredOutputCannotBeParsed() {
            when(responseSpec.entity(CitedAnswer.class))
                    .thenThrow(new IllegalStateException("not valid JSON"));
            when(responseSpec.content()).thenReturn("You get 13 planned leaves each calendar year.");

            ChatResponse response = ask(ChatService.MODE_MODEL_ATTRIBUTED);

            assertThat(response.answer()).isEqualTo("You get 13 planned leaves each calendar year.");
            assertThat(response.sources()).hasSize(2);     // all retrieved documents
        }

        @Test
        void fallsBackWhenStructuredAnswerIsBlank() {
            modelReturns(new CitedAnswer("   ", List.of("S1")));
            when(responseSpec.content()).thenReturn("Plain answer.");

            ChatResponse response = ask(ChatService.MODE_MODEL_ATTRIBUTED);

            assertThat(response.answer()).isEqualTo("Plain answer.");
        }
    }

    @Nested
    @DisplayName("retrieval-scoped mode")
    class RetrievalScoped {

        @Test
        void citesEveryRetrievedPage() {
            when(responseSpec.content()).thenReturn("You get 13 planned leaves each calendar year.");

            ChatResponse response = ask(ChatService.MODE_RETRIEVAL_SCOPED);

            assertThat(response.sources()).containsExactly(
                    "Leave Policy.pdf (pages 1, 2, 3)",
                    "OverTime Policy.pdf (page 1)");
        }

        @Test
        void reportsRetrievedChunkCountAndMetadata() {
            when(responseSpec.content()).thenReturn("Answer text.");

            ChatResponse response = ask(ChatService.MODE_RETRIEVAL_SCOPED);

            assertThat(response.chunksRetrieved()).isEqualTo(3);
            assertThat(response.retrievedChunks()).hasSize(3);
            assertThat(response.retrievedChunks().getFirst().pageNumber()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("source labels")
    class SourceLabels {

        @Test
        void areOneBasedAndSequential() {
            assertThat(PromptBuilder.sourceLabel(0)).isEqualTo("S1");
            assertThat(PromptBuilder.sourceLabel(4)).isEqualTo("S5");
        }

        @Test
        void appearInTheCitedPrompt() {
            String prompt = new PromptBuilder().buildCitedPrompt(DOCUMENTS, "How many leaves?");

            assertThat(prompt).contains("[S1] from Leave Policy.pdf");
            assertThat(prompt).contains("[S3] from OverTime Policy.pdf");
            assertThat(prompt).contains("Do NOT invent labels");
        }
    }
}
