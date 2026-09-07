package com.example.hrbot.ingestion;

import com.example.hrbot.ingestion.TextChunkingService.TextChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TextChunkingServiceTest {

    private static final String DOCUMENT = "policy.pdf";

    /** Written as a code point, since the character itself is invisible in source. */
    private static final String NBSP = Character.toString(0x00A0);

    private TextChunkingService chunker(int size, int overlap) {
        return new TextChunkingService(size, overlap);
    }

    @Nested
    @DisplayName("configuration")
    class Configuration {

        @Test
        void rejectsOverlapGreaterThanOrEqualToChunkSize() {
            assertThatThrownBy(() -> chunker(100, 100))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must be less than chunk size");
        }

        @Test
        void rejectsNonPositiveChunkSize() {
            assertThatThrownBy(() -> chunker(0, 0))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejectsNegativeOverlap() {
            assertThatThrownBy(() -> chunker(100, -1))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("empty and short input")
    class EdgeCases {

        @Test
        void returnsNothingForNullText() {
            assertThat(chunker(100, 10).chunkText(null, DOCUMENT)).isEmpty();
        }

        @Test
        void returnsNothingForBlankText() {
            assertThat(chunker(100, 10).chunkText("   \n\n  ", DOCUMENT)).isEmpty();
        }

        @Test
        void returnsSingleChunkWhenTextFitsInOne() {
            List<TextChunk> chunks = chunker(100, 10).chunkText("Short policy text.", DOCUMENT);

            assertThat(chunks).hasSize(1);
            assertThat(chunks.getFirst().content()).isEqualTo("Short policy text.");
            assertThat(chunks.getFirst().chunkIndex()).isZero();
            assertThat(chunks.getFirst().documentName()).isEqualTo(DOCUMENT);
        }
    }

    @Nested
    @DisplayName("boundary alignment")
    class BoundaryAlignment {

        /**
         * The property that matters most for retrieval quality: a chunk must never begin
         * or end inside a word, because the embedding is computed from whatever text the
         * chunk contains and fragments carry little meaning.
         */
        @Test
        void neverSplitsAWord() {
            String text = ("Employees are entitled to thirteen planned leaves each calendar year. "
                    + "Unplanned leaves are capped at five per year. "
                    + "Wedding leave requires completion of probation. ").repeat(12);

            List<TextChunk> chunks = chunker(200, 40).chunkText(text, DOCUMENT);

            assertThat(chunks).hasSizeGreaterThan(1);
            for (TextChunk chunk : chunks) {
                assertThat(chunk.content()).isNotBlank();
                assertThat(chunk.content()).doesNotStartWith(" ");
                assertThat(startsMidWord(text, chunk))
                        .as("chunk %d starts mid-word: '%s'", chunk.chunkIndex(),
                                chunk.content().substring(0, Math.min(30, chunk.content().length())))
                        .isFalse();
            }
        }

        private boolean startsMidWord(String text, TextChunk chunk) {
            int start = chunk.startOffset();
            if (start == 0) {
                return false;
            }
            char previous = text.charAt(start - 1);
            char first = text.charAt(start);
            return !Character.isWhitespace(previous) && !Character.isWhitespace(first);
        }

        @Test
        void prefersParagraphBoundaries() {
            String text = "First paragraph about planned leave.\n\nSecond paragraph about sick leave.";

            List<TextChunk> chunks = chunker(45, 0).chunkText(text, DOCUMENT);

            assertThat(chunks).hasSize(2);
            assertThat(chunks.get(0).content()).isEqualTo("First paragraph about planned leave.");
            assertThat(chunks.get(1).content()).isEqualTo("Second paragraph about sick leave.");
        }

        @Test
        void fallsBackToWordBoundaryWhenNoSentenceBreakExists() {
            String text = "alpha bravo charlie delta echo foxtrot golf hotel india juliett kilo lima";

            List<TextChunk> chunks = chunker(30, 0).chunkText(text, DOCUMENT);

            assertThat(chunks).hasSizeGreaterThan(1);
            assertThat(chunks).allSatisfy(chunk ->
                    assertThat(chunk.content()).doesNotContain("  "));
        }

        @Test
        void splitsTextWithNoSeparatorsAtAll() {
            String text = "x".repeat(250);

            List<TextChunk> chunks = chunker(100, 0).chunkText(text, DOCUMENT);

            assertThat(chunks).hasSize(3);
            assertThat(chunks).allSatisfy(chunk ->
                    assertThat(chunk.content().length()).isLessThanOrEqualTo(100));
        }
    }

    @Nested
    @DisplayName("sizing, ordering and offsets")
    class SizingAndOffsets {

        @Test
        void respectsChunkSize() {
            String text = "This is a sentence about leave policy. ".repeat(30);

            List<TextChunk> chunks = chunker(300, 50).chunkText(text, DOCUMENT);

            assertThat(chunks).isNotEmpty();
            assertThat(chunks).allSatisfy(chunk ->
                    assertThat(chunk.content().length()).isLessThanOrEqualTo(300));
        }

        @Test
        void numbersChunksSequentiallyFromZero() {
            String text = "Sentence one. Sentence two. Sentence three. Sentence four. ".repeat(10);

            List<TextChunk> chunks = chunker(120, 20).chunkText(text, DOCUMENT);

            for (int i = 0; i < chunks.size(); i++) {
                assertThat(chunks.get(i).chunkIndex()).isEqualTo(i);
            }
        }

        @Test
        void offsetsAdvanceAndRemainWithinBounds() {
            TextChunkingService service = chunker(150, 30);
            String text = service.normalise("Leave policy detail sentence number one. ".repeat(20));

            List<TextChunk> chunks = service.chunkText(text, DOCUMENT);

            int previousStart = -1;
            for (TextChunk chunk : chunks) {
                assertThat(chunk.startOffset()).isGreaterThan(previousStart);
                assertThat(chunk.startOffset()).isLessThan(chunk.endOffset());
                assertThat(chunk.endOffset()).isLessThanOrEqualTo(text.length());
                previousStart = chunk.startOffset();
            }
        }

        @Test
        void offsetsPointAtTheChunkContent() {
            TextChunkingService service = chunker(150, 30);
            String text = service.normalise("Planned leave accrues at thirteen days per year. ".repeat(12));

            for (TextChunk chunk : service.chunkText(text, DOCUMENT)) {
                String fromOffsets = text.substring(chunk.startOffset(), chunk.endOffset());
                assertThat(fromOffsets).isEqualTo(chunk.content());
            }
        }

        /**
         * An offset resolving to whitespace resolves to the wrong page, because a page
         * join is itself whitespace and belongs to the page before the content.
         */
        @Test
        void offsetsNeverResolveToWhitespace() {
            TextChunkingService service = chunker(200, 40);
            String text = service.normalise(
                    "Page one body text about planned leave.\n\n"
                            + "Page two body text about unplanned leave.\n\n"
                            + "Page three body text about encashment rules and carry forward.");

            for (TextChunk chunk : service.chunkText(text, DOCUMENT)) {
                assertThat(Character.isWhitespace(text.charAt(chunk.startOffset())))
                        .as("chunk %d starts on whitespace", chunk.chunkIndex())
                        .isFalse();
                assertThat(Character.isWhitespace(text.charAt(chunk.endOffset() - 1)))
                        .as("chunk %d ends on whitespace", chunk.chunkIndex())
                        .isFalse();
            }
        }

        @Test
        void coversTheWholeDocument() {
            TextChunkingService service = chunker(100, 20);
            String text = service.normalise("Alpha bravo charlie delta echo. ".repeat(15));

            List<TextChunk> chunks = service.chunkText(text, DOCUMENT);

            assertThat(chunks.getFirst().startOffset()).isZero();
            assertThat(chunks.getLast().endOffset()).isEqualTo(text.length());
        }
    }

    @Nested
    @DisplayName("overlap")
    class Overlap {

        @Test
        void consecutiveChunksShareText() {
            String text = "Sentence one here. Sentence two here. Sentence three here. Sentence four here. "
                    + "Sentence five here. Sentence six here.";

            List<TextChunk> chunks = chunker(80, 40).chunkText(text, DOCUMENT);

            assertThat(chunks).hasSizeGreaterThan(1);
            for (int i = 1; i < chunks.size(); i++) {
                assertThat(chunks.get(i).startOffset())
                        .as("chunk %d should start before chunk %d ends",
                                i, i - 1)
                        .isLessThan(chunks.get(i - 1).endOffset());
            }
        }

        @Test
        void producesNoOverlapWhenConfiguredToZero() {
            String text = "Sentence one. Sentence two. Sentence three. Sentence four. ".repeat(6);

            List<TextChunk> chunks = chunker(80, 0).chunkText(text, DOCUMENT);

            for (int i = 1; i < chunks.size(); i++) {
                assertThat(chunks.get(i).startOffset())
                        .isGreaterThanOrEqualTo(chunks.get(i - 1).endOffset());
            }
        }

        @Test
        void terminatesWhenOverlapIsNearlyTheWholeChunk() {
            String text = "Alpha bravo charlie delta echo foxtrot golf hotel. ".repeat(20);

            List<TextChunk> chunks = chunker(100, 99).chunkText(text, DOCUMENT);

            assertThat(chunks).isNotEmpty();
            assertThat(chunks.size()).isLessThan(2000);
        }
    }

    @Nested
    @DisplayName("normalisation")
    class Normalisation {

        /**
         * Callers record page offsets against normalised text, so normalising twice must
         * not shift any character.
         */
        @Test
        void isIdempotent() {
            TextChunkingService service = chunker(100, 10);
            String messy = "Leave  policy" + NBSP + "text\n\n\n\nwith   noise   \nand trailing space   ";

            String once = service.normalise(messy);
            String twice = service.normalise(once);

            assertThat(twice).isEqualTo(once);
        }

        @Test
        void collapsesWhitespaceAndPreservesParagraphBreaks() {
            String result = chunker(100, 10)
                    .normalise("First   paragraph.\n\n\n\nSecond paragraph.");

            assertThat(result).isEqualTo("First paragraph.\n\nSecond paragraph.");
        }
    }
}
