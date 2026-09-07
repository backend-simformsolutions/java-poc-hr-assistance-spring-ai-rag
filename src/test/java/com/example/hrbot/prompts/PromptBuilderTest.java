package com.example.hrbot.prompts;

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
    @DisplayName("wording shared by both templates")
    class SharedWording {

        /**
         * The two templates serve different output contracts, but a refusal must read the
         * same whichever one produced it. Both therefore interpolate one constant.
         */
        @Test
        void bothTemplatesCarryTheSameRefusalSentence() {
            assertThat(promptBuilder.getTemplatePreview())
                    .contains(PromptBuilder.REFUSAL_SENTENCE);
            assertThat(promptBuilder.getCitedTemplatePreview())
                    .contains(PromptBuilder.REFUSAL_SENTENCE);
        }

        @Test
        void refusalSentenceAppearsOncePerTemplate() {
            assertThat(occurrences(promptBuilder.getTemplatePreview(),
                    PromptBuilder.REFUSAL_SENTENCE)).isEqualTo(1);
            assertThat(occurrences(promptBuilder.getCitedTemplatePreview(),
                    PromptBuilder.REFUSAL_SENTENCE)).isEqualTo(1);
        }

        @Test
        void bothTemplatesConstrainTheModelToSuppliedTextTablesAndExactValues() {
            for (String template : List.of(
                    promptBuilder.getTemplatePreview(),
                    promptBuilder.getCitedTemplatePreview())) {
                assertThat(template).contains("Do NOT use outside");
                assertThat(template).contains("include ALL rows/items");
                assertThat(template).contains("quote the EXACT value");
            }
        }

        /** One slot for context and one for the question; anything else misformats. */
        @Test
        void eachTemplateHasExactlyTwoPlaceholders() {
            assertThat(occurrences(promptBuilder.getTemplatePreview(), "%s")).isEqualTo(2);
            assertThat(occurrences(promptBuilder.getCitedTemplatePreview(), "%s")).isEqualTo(2);
        }

        /** Interpolation must leave no marker behind in either template. */
        @Test
        void noUnresolvedInterpolationMarkersRemain() {
            assertThat(promptBuilder.getTemplatePreview()).doesNotContain("{refusal}");
            assertThat(promptBuilder.getCitedTemplatePreview()).doesNotContain("{refusal}");
        }
    }

    @Nested
    @DisplayName("differences that distinguish the two templates")
    class ContractDifferences {

        @Test
        void citedPromptLabelsEachSourceAndNamesItsDocument() {
            String prompt = promptBuilder.buildCitedPrompt(
                    List.of(document("13 planned leaves per year.", "Leave Policy.pdf"),
                            document("Overtime is paid at 1.5x.", "OverTime Policy.pdf")),
                    "How many planned leaves?");

            assertThat(prompt)
                    .contains("[S1] from Leave Policy.pdf")
                    .contains("[S2] from OverTime Policy.pdf")
                    .contains("13 planned leaves per year.")
                    .contains("How many planned leaves?");
        }

        /**
         * The plain template backs retrieval-scoped mode and the attribution fallback; in
         * both the reply is read as prose. Source labels would invite the model to quote
         * identifiers it was never asked to use.
         */
        @Test
        void plainPromptCarriesNoSourceLabels() {
            String prompt = promptBuilder.buildPrompt(
                    List.of(document("13 planned leaves per year.", "Leave Policy.pdf")),
                    "How many planned leaves?");

            assertThat(prompt)
                    .doesNotContain("[S1]")
                    .contains("=== Leave Policy.pdf ===");
            assertThat(prompt.stripTrailing()).endsWith("Answer:");
        }

        /** Only the cited contract asks for citations, and only it forbids inventing them. */
        @Test
        void onlyTheCitedTemplateRequestsCitations() {
            assertThat(promptBuilder.getCitedTemplatePreview())
                    .contains("\"citations\"")
                    .contains("Do NOT invent labels");
            assertThat(promptBuilder.getTemplatePreview())
                    .doesNotContain("\"citations\"");
        }
    }

    @Nested
    @DisplayName("empty retrieval")
    class EmptyRetrieval {

        @Test
        void bothBuildersStateThatNothingWasFound() {
            assertThat(promptBuilder.buildPrompt(List.of(), "Anything?"))
                    .contains("[No relevant HR policy documents were found for this question.]");
            assertThat(promptBuilder.buildCitedPrompt(List.of(), "Anything?"))
                    .contains("[No relevant HR policy documents were found for this question.]");
        }
    }
}
