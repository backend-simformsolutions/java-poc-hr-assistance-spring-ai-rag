package com.example.hrbot.prompts;

import com.example.hrbot.prompts.PromptBuilder.GroundedPrompt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PromptBuilderTest {

    private final PromptBuilder promptBuilder = new PromptBuilder();

    private static Document document(String text, String documentName) {
        return new Document(text, Map.of("documentName", documentName));
    }

    private static int occurrences(String text, String token) {
        return text.split(java.util.regex.Pattern.quote(token), -1).length - 1;
    }

    @Nested
    @DisplayName("role split across system and user messages")
    class RoleSplit {

        /**
         * Chunk text and document names come from uploaded files. Keeping the rules in the
         * system message means retrieved content never sits alongside them with equal
         * standing.
         */
        @Test
        void rulesLiveInTheSystemMessageAndContextInTheUserMessage() {
            GroundedPrompt prompt = promptBuilder.buildCitedPrompt(
                    List.of(document("13 planned leaves per year.", "Leave Policy.pdf")),
                    "How many planned leaves?");

            assertThat(prompt.system())
                    .contains("You are an HR Policy Assistant")
                    .contains("RULES:")
                    .doesNotContain("13 planned leaves per year.")
                    .doesNotContain("How many planned leaves?");

            assertThat(prompt.user())
                    .contains("13 planned leaves per year.")
                    .contains("How many planned leaves?")
                    .doesNotContain("RULES:");
        }

        @Test
        void bothModesSplitTheSameWay() {
            GroundedPrompt plain = promptBuilder.buildPrompt(
                    List.of(document("Body text.", "Leave Policy.pdf")), "A question?");

            assertThat(plain.system()).contains("RULES:").doesNotContain("A question?");
            assertThat(plain.user()).contains("A question?").doesNotContain("RULES:");
        }

        /** The system message is fixed per mode, so it is a stable prefix across requests. */
        @Test
        void theSystemMessageDoesNotVaryWithTheRequest() {
            String first = promptBuilder.buildCitedPrompt(
                    List.of(document("One.", "A.pdf")), "First question?").system();
            String second = promptBuilder.buildCitedPrompt(
                    List.of(document("Two.", "B.pdf")), "Second question?").system();

            assertThat(first).isEqualTo(second).isEqualTo(promptBuilder.getCitedSystemPrompt());
        }
    }

    @Nested
    @DisplayName("wording shared by both modes")
    class SharedWording {

        /**
         * The two modes serve different output contracts, but a refusal must read the same
         * whichever one produced it. Both therefore interpolate one constant.
         */
        @Test
        void bothSystemMessagesCarryTheSameRefusalSentence() {
            assertThat(promptBuilder.getSystemPrompt())
                    .contains(PromptBuilder.REFUSAL_SENTENCE);
            assertThat(promptBuilder.getCitedSystemPrompt())
                    .contains(PromptBuilder.REFUSAL_SENTENCE);
        }

        @Test
        void refusalSentenceAppearsOncePerSystemMessage() {
            assertThat(occurrences(promptBuilder.getSystemPrompt(),
                    PromptBuilder.REFUSAL_SENTENCE)).isEqualTo(1);
            assertThat(occurrences(promptBuilder.getCitedSystemPrompt(),
                    PromptBuilder.REFUSAL_SENTENCE)).isEqualTo(1);
        }

        @Test
        void bothSystemMessagesConstrainToSuppliedTextTablesAndExactValues() {
            for (String system : List.of(
                    promptBuilder.getSystemPrompt(),
                    promptBuilder.getCitedSystemPrompt())) {
                assertThat(system).contains("Do NOT use outside");
                assertThat(system).contains("include ALL rows/items");
                assertThat(system).contains("quote the EXACT value");
            }
        }

        /** One slot for context and one for the question in each user template. */
        @Test
        void eachUserTemplateHasExactlyTwoPlaceholders() {
            assertThat(occurrences(promptBuilder.getUserTemplate(), "%s")).isEqualTo(2);
            assertThat(occurrences(promptBuilder.getCitedUserTemplate(), "%s")).isEqualTo(2);
        }

        /** Interpolation must leave no marker behind in either system message. */
        @Test
        void noUnresolvedInterpolationMarkersRemain() {
            assertThat(promptBuilder.getSystemPrompt()).doesNotContain("{refusal}");
            assertThat(promptBuilder.getCitedSystemPrompt()).doesNotContain("{refusal}");
        }
    }

    @Nested
    @DisplayName("differences that distinguish the two modes")
    class ContractDifferences {

        @Test
        void citedPromptLabelsEachSourceAndNamesItsDocument() {
            GroundedPrompt prompt = promptBuilder.buildCitedPrompt(
                    List.of(document("13 planned leaves per year.", "Leave Policy.pdf"),
                            document("Overtime is paid at 1.5x.", "OverTime Policy.pdf")),
                    "How many planned leaves?");

            assertThat(prompt.user())
                    .contains("[S1] from Leave Policy.pdf")
                    .contains("[S2] from OverTime Policy.pdf")
                    .contains("13 planned leaves per year.");
        }

        /**
         * The plain mode is read as prose, so its user message ends by inviting an answer.
         * Source labels would invite the model to quote identifiers it was never given.
         */
        @Test
        void plainPromptCarriesNoSourceLabelsAndInvitesAnAnswer() {
            GroundedPrompt prompt = promptBuilder.buildPrompt(
                    List.of(document("13 planned leaves per year.", "Leave Policy.pdf")),
                    "How many planned leaves?");

            assertThat(prompt.user())
                    .doesNotContain("[S1]")
                    .contains("=== Leave Policy.pdf ===");
            assertThat(prompt.user().stripTrailing()).endsWith("Answer:");
        }

        /**
         * The cited user message ends on the question: the structured-output converter
         * appends its schema instruction there.
         */
        @Test
        void citedPromptLeavesRoomForTheSchemaInstruction() {
            GroundedPrompt prompt = promptBuilder.buildCitedPrompt(
                    List.of(document("Body.", "A.pdf")), "A question?");

            assertThat(prompt.user().stripTrailing()).endsWith("A question?");
        }

        /** Only the cited contract asks for citations, and only it forbids inventing them. */
        @Test
        void onlyTheCitedModeRequestsCitations() {
            assertThat(promptBuilder.getCitedSystemPrompt())
                    .contains("\"citations\"")
                    .contains("Do NOT invent labels");
            assertThat(promptBuilder.getSystemPrompt())
                    .doesNotContain("\"citations\"");
        }
    }

    @Nested
    @DisplayName("empty retrieval")
    class EmptyRetrieval {

        @Test
        void bothModesStateThatNothingWasFound() {
            assertThat(promptBuilder.buildPrompt(List.of(), "Anything?").user())
                    .contains("[No relevant HR policy documents were found for this question.]");
            assertThat(promptBuilder.buildCitedPrompt(List.of(), "Anything?").user())
                    .contains("[No relevant HR policy documents were found for this question.]");
        }
    }
}
