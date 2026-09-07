package com.example.hrbot.ingestion;

/**
 * Whitespace normalisation for extracted document text.
 *
 * <p>Extraction records the character offset at which each page begins, and chunking
 * records the offsets of each chunk. Both sets of offsets index the same string, so both
 * stages have to normalise text identically — a single definition here is what makes
 * that hold.
 */
final class TextNormaliser {

    /**
     * Non-breaking space, written as an escape rather than as a literal: the character
     * is invisible in source, and an ordinary space in its place looks identical.
     */
    private static final char NON_BREAKING_SPACE = '\u00A0';

    private TextNormaliser() {
    }

    /**
     * Reduces whitespace to a predictable form: line endings become {@code \n},
     * non-breaking spaces become ordinary ones, runs of spaces and tabs collapse,
     * trailing whitespace is dropped from each line, and runs of blank lines reduce to a
     * single paragraph break.
     *
     * <p>Line endings are normalised first because PDFBox emits the platform separator.
     * Left as {@code \r\n}, a paragraph break would not match the {@code "\n\n"}
     * separator that chunking splits on, so the same document would chunk differently on
     * Windows than on Linux.
     *
     * <p>Idempotent. Normalising already-normalised text returns it unchanged, so an
     * offset recorded against the output stays valid no matter how many times the text
     * passes through.
     *
     * @param text text to normalise
     * @return the normalised text, trimmed
     */
    static String normalise(String text) {
        return text
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace(NON_BREAKING_SPACE, ' ')
                .replaceAll("[ \t]{2,}", " ")
                .replaceAll("(?m)[ \t]+$", "")
                .replaceAll("\n{3,}", "\n\n")
                .trim();
    }
}
