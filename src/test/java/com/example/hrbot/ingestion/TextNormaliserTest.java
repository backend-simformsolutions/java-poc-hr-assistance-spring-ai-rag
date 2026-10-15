package com.example.hrbot.ingestion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Page citations depend on extraction and chunking agreeing on offsets, which holds only
 * while both normalise text identically. These tests pin that agreement.
 */
class TextNormaliserTest {

    private static final String NBSP = Character.toString(0x00A0);

    @Test
    @DisplayName("a non-breaking space becomes an ordinary space")
    void convertsNonBreakingSpace() {
        assertThat(TextNormaliser.normalise("Section 1:" + NBSP + "Leave"))
                .isEqualTo("Section 1: Leave")
                .doesNotContain(NBSP);
    }

    /**
     * The case that shifts offsets: a non-breaking space beside an ordinary one becomes
     * a run of two spaces, which then collapses to one and shortens the text.
     */
    @Test
    @DisplayName("a non-breaking space next to a space collapses to a single space")
    void collapsesNonBreakingSpaceAdjacentToSpace() {
        String normalised = TextNormaliser.normalise("Section 1:" + NBSP + " Leave");

        assertThat(normalised).isEqualTo("Section 1: Leave");
        assertThat(normalised).doesNotContain("  ");
    }

    /**
     * Chunking normalises text that extraction already normalised. If the second pass
     * changed anything, every page offset recorded by the first would be wrong.
     */
    @Test
    @DisplayName("normalising twice changes nothing")
    void isIdempotent() {
        String messy = "Title" + NBSP + " One\t\tvalue   here   \n\n\n\nNext" + NBSP + "page  \n";

        String once = TextNormaliser.normalise(messy);
        String twice = TextNormaliser.normalise(once);

        assertThat(twice).isEqualTo(once);
        assertThat(once.length()).isEqualTo(twice.length());
    }

    @Test
    @DisplayName("the chunking service applies exactly this normalisation")
    void chunkingServiceDelegatesHere() {
        String messy = "Leave" + NBSP + " Policy   with\t\truns   \n\n\n\nand pages  ";

        assertThat(new TextChunkingService(1000, 50).normalise(messy))
                .isEqualTo(TextNormaliser.normalise(messy));
    }

    /**
     * PDFBox emits the platform line separator. Left as {@code \r\n}, a paragraph break
     * contains no two adjacent newlines, so chunking's strongest separator would never
     * match and the same document would chunk differently per platform.
     */
    @Test
    @DisplayName("CRLF and lone CR become LF")
    void normalisesLineEndings() {
        assertThat(TextNormaliser.normalise("one\r\n\r\ntwo\r\nthree\rfour"))
                .isEqualTo("one\n\ntwo\nthree\nfour")
                .doesNotContain("\r");
    }

    @Test
    @DisplayName("a CRLF paragraph break survives as a single LF paragraph break")
    void keepsParagraphBreaksAcrossLineEndingStyles() {
        assertThat(TextNormaliser.normalise("para one\r\n\r\n\r\n\r\npara two"))
                .isEqualTo("para one\n\npara two");
    }

    @Test
    @DisplayName("normalising CRLF text twice changes nothing")
    void isIdempotentOnWindowsLineEndings() {
        String once = TextNormaliser.normalise("a\r\n\r\nb   \r\nc" + NBSP + " d");

        assertThat(TextNormaliser.normalise(once)).isEqualTo(once);
    }

    @Test
    @DisplayName("runs of spaces and tabs collapse, trailing whitespace is dropped")
    void collapsesWhitespaceRuns() {
        assertThat(TextNormaliser.normalise("a    b\t\tc   \nd  ")).isEqualTo("a b c\nd");
    }

    @Test
    @DisplayName("paragraph breaks survive but longer runs of blank lines do not")
    void keepsParagraphBreaks() {
        assertThat(TextNormaliser.normalise("one\n\ntwo\n\n\n\n\nthree"))
                .isEqualTo("one\n\ntwo\n\nthree");
    }
}
