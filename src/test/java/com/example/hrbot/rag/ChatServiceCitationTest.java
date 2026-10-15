package com.example.hrbot.rag;

import com.example.hrbot.dto.ChatRequest;
import com.example.hrbot.dto.ChatResponse;
import com.example.hrbot.dto.ChatStreamEvent;
import com.example.hrbot.dto.CitedAnswer;
import com.example.hrbot.prompts.PromptBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
    }

    private static final Duration STREAM_TIMEOUT = Duration.ofSeconds(30);

    private ChatService service(String mode) {
        return new ChatService(retrievalService, new PromptBuilder(), chatClient, mode, STREAM_TIMEOUT);
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

        /**
         * A refusal states that the retrieved text does not answer the question, so it
         * must carry no citations. Citing the pages searched would present the refusal as
         * though it had been drawn from them.
         */
        @Test
        void aRefusalCitesNothing() {
            modelReturns(new CitedAnswer(PromptBuilder.REFUSAL_SENTENCE, List.of()));

            ChatResponse response = ask(ChatService.MODE_MODEL_ATTRIBUTED);

            assertThat(response.answer()).isEqualTo(PromptBuilder.REFUSAL_SENTENCE);
            assertThat(response.sources()).isEmpty();
        }

        /** A refusal cites nothing even if the model also reports labels. */
        @Test
        void aRefusalCitesNothingEvenWhenLabelsAreReported() {
            modelReturns(new CitedAnswer(PromptBuilder.REFUSAL_SENTENCE, List.of("S1", "S2")));

            assertThat(ask(ChatService.MODE_MODEL_ATTRIBUTED).sources()).isEmpty();
        }

        /** A model that reflows the sentence across lines is still refusing. */
        @Test
        void aRefusalIsRecognisedDespiteReflowedWhitespace() {
            modelReturns(new CitedAnswer(
                    "I could not find this information in the HR policies\nprovided.   Please"
                            + " contact the HR department directly.",
                    List.of()));

            assertThat(ask(ChatService.MODE_MODEL_ATTRIBUTED).sources()).isEmpty();
        }

        /** A model that drops the closing clause is still refusing. */
        @Test
        void aRefusalIsRecognisedWithoutItsClosingClause() {
            modelReturns(new CitedAnswer(
                    "I could not find this information in the HR policies provided.",
                    List.of()));

            assertThat(ask(ChatService.MODE_MODEL_ATTRIBUTED).sources()).isEmpty();
        }

        /**
         * A multi-part question can be half answered: the model resolves what it found and
         * appends the refusal for the rest. That answer carries real policy facts, so it
         * must keep its citations — matching the refusal by containment would strip them.
         */
        @Test
        void anAnswerThatMerelyEndsInARefusalKeepsItsCitations() {
            modelReturns(new CitedAnswer(
                    "You get 13 planned leaves each calendar year. "
                            + PromptBuilder.REFUSAL_SENTENCE,
                    List.of("S1")));

            ChatResponse response = ask(ChatService.MODE_MODEL_ATTRIBUTED);

            assertThat(response.sources()).containsExactly("Leave Policy.pdf (page 1)");
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

        /** This mode cites everything retrieved — except when the answer is a refusal. */
        @Test
        void aRefusalCitesNothing() {
            when(responseSpec.content()).thenReturn(PromptBuilder.REFUSAL_SENTENCE);

            ChatResponse response = ask(ChatService.MODE_RETRIEVAL_SCOPED);

            assertThat(response.answer()).isEqualTo(PromptBuilder.REFUSAL_SENTENCE);
            assertThat(response.sources()).isEmpty();
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
    @DisplayName("streaming")
    class Streaming {

        private ChatClient.StreamResponseSpec streamSpec;

        /** What the model streams for the answer, then for the attribution call. */
        private Flux<String> answer = Flux.empty();
        private Flux<String> attribution = Flux.empty();

        @BeforeEach
        void setUpStream() {
            streamSpec = mock(ChatClient.StreamResponseSpec.class);
            when(requestSpec.stream()).thenReturn(streamSpec);
        }

        private void modelStreams(String... tokens) {
            answer = Flux.just(tokens);
        }

        /** The attribution reply arrives in pieces, as a streamed JSON object. */
        private void attributionReports(String... labels) {
            String json = Arrays.stream(labels)
                    .map(label -> "\"" + label + "\"")
                    .collect(Collectors.joining(",", "{\"citations\": [", "]}"));
            attribution = Flux.just(json.substring(0, 5), json.substring(5));
        }

        private List<ChatStreamEvent> streamAnswer(String mode, Duration timeout) {
            when(streamSpec.content()).thenReturn(answer, attribution);
            return new ChatService(retrievalService, new PromptBuilder(), chatClient, mode, timeout)
                    .streamChat(new ChatRequest("How many planned leaves do I get?", null))
                    .collectList()
                    .block();
        }

        private List<ChatStreamEvent> streamAnswer() {
            return streamAnswer(ChatService.MODE_MODEL_ATTRIBUTED, STREAM_TIMEOUT);
        }

        private ChatStreamEvent.Done done(List<ChatStreamEvent> events) {
            assertThat(events.getLast()).isInstanceOf(ChatStreamEvent.Done.class);
            return (ChatStreamEvent.Done) events.getLast();
        }

        @Test
        void emitsRetrievalThenEachTokenInOrderThenDone() {
            modelStreams("You get", " 13 planned", " leaves.");
            attributionReports("S1");

            List<ChatStreamEvent> events = streamAnswer();

            assertThat(events).hasSize(5);
            ChatStreamEvent.Retrieval retrieval = (ChatStreamEvent.Retrieval) events.getFirst();
            assertThat(retrieval.chunksRetrieved()).isEqualTo(3);
            assertThat(retrieval.retrievedChunks()).hasSize(3);
            assertThat(retrieval.requestId()).isNotBlank();
            // Leading spaces belong to the token and must survive.
            assertThat(events.subList(1, 4)).containsExactly(
                    new ChatStreamEvent.Token("You get"),
                    new ChatStreamEvent.Token(" 13 planned"),
                    new ChatStreamEvent.Token(" leaves."));
            assertThat(events.getLast()).isInstanceOf(ChatStreamEvent.Done.class);
        }

        /**
         * The answer comes from the plain prompt, so it carries no labels; the labelled
         * sources, the finished answer and the reply schema go to the attribution call.
         */
        @Test
        void answersFromThePlainPromptThenAttributesTheCompletedAnswer() {
            modelStreams("You get 13 planned", " leaves each calendar year.");
            attributionReports("S1");

            streamAnswer();

            PromptBuilder prompts = new PromptBuilder();
            verify(requestSpec).system(prompts.getSystemPrompt());
            verify(requestSpec).system(prompts.getAttributionSystemPrompt());
            verify(requestSpec).user(argThat((String user) ->
                    user.contains("[S1] from Leave Policy.pdf")
                            && user.contains("You get 13 planned leaves each calendar year.")
                            && user.contains("\"citations\"")));
            // Both calls stream, so a disconnect can cancel either; neither blocks.
            verify(requestSpec, times(2)).stream();
            verify(requestSpec, never()).call();
        }

        @Test
        void citesOnlyThePagesTheAttributionNames() {
            modelStreams("You get 13 planned leaves each calendar year.");
            attributionReports("S1");

            assertThat(done(streamAnswer()).sources())
                    .containsExactly("Leave Policy.pdf (page 1)");
        }

        @Test
        void groupsSeveralAttributedSourcesByDocument() {
            modelStreams("Leave and overtime detail.");
            attributionReports("S3", "S1", "S2");

            assertThat(done(streamAnswer()).sources()).containsExactly(
                    "OverTime Policy.pdf (page 1)",
                    "Leave Policy.pdf (pages 1, 2, 3)");
        }

        /** The same label validation as the blocking endpoint: invented labels are dropped. */
        @Test
        void ignoresInventedLabels() {
            modelStreams("You get 13 planned leaves.");
            attributionReports("S1", "S9", "[s1]");

            assertThat(done(streamAnswer()).sources())
                    .containsExactly("Leave Policy.pdf (page 1)");
        }

        /** Same safety net as the model-attributed mode: less precise, never false. */
        @Test
        void fallsBackToAllRetrievedPagesWhenNoLabelIsVerified() {
            modelStreams("You get 13 planned leaves each calendar year.");
            attributionReports("S7");

            assertThat(done(streamAnswer()).sources()).containsExactly(
                    "Leave Policy.pdf (pages 1, 2, 3)",
                    "OverTime Policy.pdf (page 1)");
        }

        /**
         * The answer has already reached the client when attribution runs, so a failure
         * there must not turn the stream into an error.
         */
        @Test
        void fallsBackToAllRetrievedPagesWhenAttributionFails() {
            modelStreams("You get 13 planned leaves each calendar year.");
            attribution = Flux.error(new IllegalStateException("connection to Ollama reset"));

            assertThat(done(streamAnswer()).sources()).containsExactly(
                    "Leave Policy.pdf (pages 1, 2, 3)",
                    "OverTime Policy.pdf (page 1)");
        }

        @Test
        void fallsBackToAllRetrievedPagesWhenTheAttributionReplyIsNotJson() {
            modelStreams("You get 13 planned leaves each calendar year.");
            attribution = Flux.just("I think S1 is the source.");

            assertThat(done(streamAnswer()).sources()).hasSize(2);
        }

        /** Retrieval-scoped mode cites every retrieved page and skips the second call. */
        @Test
        void retrievalScopedModeCitesEveryRetrievedPageWithoutAttribution() {
            modelStreams("You get 13 planned leaves each calendar year.");

            List<ChatStreamEvent> events = streamAnswer(ChatService.MODE_RETRIEVAL_SCOPED, STREAM_TIMEOUT);

            assertThat(done(events).sources()).containsExactly(
                    "Leave Policy.pdf (pages 1, 2, 3)",
                    "OverTime Policy.pdf (page 1)");
            verify(requestSpec, times(1)).stream();
        }

        /** A refusal cites nothing, and there is nothing to attribute. */
        @Test
        void aRefusalCitesNothingAndSkipsAttribution() {
            modelStreams("I could not find this information", " in the HR policies provided.",
                    " Please contact the HR department directly.");

            assertThat(done(streamAnswer()).sources()).isEmpty();
            verify(requestSpec, times(1)).stream();
        }

        @Test
        void anAnswerThatMerelyEndsInARefusalKeepsItsCitations() {
            modelStreams("You get 13 planned leaves. " + PromptBuilder.REFUSAL_SENTENCE);
            attributionReports("S1");

            assertThat(done(streamAnswer()).sources())
                    .containsExactly("Leave Policy.pdf (page 1)");
        }

        /**
         * The HTTP status has already been sent once streaming starts, so a failure part
         * way through must arrive as a terminal event after the text already delivered.
         */
        @Test
        void aFailureDuringGenerationEndsTheStreamWithAnError() {
            answer = Flux.concat(
                    Flux.just("You get 13"),
                    Flux.error(new IllegalStateException("connection to Ollama reset")));

            List<ChatStreamEvent> events = streamAnswer();

            assertThat(events).hasSize(3);
            assertThat(events.get(1)).isEqualTo(new ChatStreamEvent.Token("You get 13"));
            ChatStreamEvent.Error error = (ChatStreamEvent.Error) events.getLast();
            // The reference matches the retrieval event, and internal detail stays in the log.
            assertThat(error.requestId())
                    .isEqualTo(((ChatStreamEvent.Retrieval) events.getFirst()).requestId());
            assertThat(error.message())
                    .contains(error.requestId())
                    .doesNotContain("Ollama reset");
            verify(requestSpec, times(1)).stream();
        }

        @Test
        void anEmptyAnswerEndsTheStreamWithAnError() {
            modelStreams("", "  ");

            List<ChatStreamEvent> events = streamAnswer();

            assertThat(events.getLast()).isInstanceOf(ChatStreamEvent.Error.class);
        }

        /** A stalled model must still end the stream with a terminal event. */
        @Test
        void aStreamThatOverrunsTheDeadlineEndsWithAnError() {
            answer = Flux.concat(Flux.just("You get"), Flux.never());

            List<ChatStreamEvent> events =
                    streamAnswer(ChatService.MODE_MODEL_ATTRIBUTED, Duration.ofMillis(300));

            assertThat(events).hasSize(3);
            assertThat(events.get(1)).isEqualTo(new ChatStreamEvent.Token("You get"));
            assertThat(events.getLast()).isInstanceOf(ChatStreamEvent.Error.class);
        }

        /** The deadline covers attribution too, not only generation. */
        @Test
        void theDeadlineAlsoCoversAttribution() {
            modelStreams("You get 13 planned leaves each calendar year.");
            attribution = Flux.never();

            List<ChatStreamEvent> events =
                    streamAnswer(ChatService.MODE_MODEL_ATTRIBUTED, Duration.ofMillis(300));

            assertThat(events.getLast()).isInstanceOf(ChatStreamEvent.Error.class);
        }

        /** Retrieval runs before the stream exists, so its failure is an ordinary exception. */
        @Test
        void aRetrievalFailureIsThrownBeforeStreamingStarts() {
            when(retrievalService.retrieveDocuments(anyString(), any()))
                    .thenThrow(new IllegalStateException("database unavailable"));

            assertThatThrownBy(() -> service(ChatService.MODE_MODEL_ATTRIBUTED)
                    .streamChat(new ChatRequest("Anything?", null)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("database unavailable");
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
            PromptBuilder.GroundedPrompt prompt =
                    new PromptBuilder().buildCitedPrompt(DOCUMENTS, "How many leaves?");

            assertThat(prompt.user()).contains("[S1] from Leave Policy.pdf");
            assertThat(prompt.user()).contains("[S3] from OverTime Policy.pdf");
            assertThat(prompt.system()).contains("Do NOT invent labels");
        }
    }
}
