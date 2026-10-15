package com.example.hrbot.ingestion;

import com.example.hrbot.exception.DocumentProcessingException;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Extracts plain text from PDF bytes, page by page, using Apache PDFBox.
 *
 * <p>A PDF describes glyph placement rather than text, so {@link PDFTextStripper} walks
 * the page tree, resolves font encodings and emits characters in reading order.
 *
 * <h2>Page tracking</h2>
 * Pages are extracted individually and concatenated, recording the character offset at
 * which each begins. A caller can then translate any offset in the combined text back
 * to a page number, which is what makes a citation verifiable: a reader can open the
 * cited page and check the claim. See {@link ExtractedDocument#pageOf(int)}.
 *
 * <h2>Limitations</h2>
 * <ul>
 *   <li><b>Scanned PDFs</b> have no text layer; extraction yields nothing and OCR
 *       (for example Tesseract) would be required.</li>
 *   <li><b>Tables and columns</b> lose their layout. Text is emitted linearly, so a
 *       table becomes a sequence of lines with no column structure. Use
 *       {@link PDFTextStripper#setSortByPosition(boolean)} or a dedicated table
 *       extractor when layout fidelity matters.</li>
 *   <li><b>Memory</b> — the whole document is loaded into a {@link PDDocument}.
 *       Very large files should be streamed page by page instead.</li>
 * </ul>
 */
@Slf4j
@Service
public class PdfTextExtractor {

    /**
     * Print artefacts that documents exported from web or office tools carry on every
     * page: an export timestamp line, absolute URLs and standalone "page n/m" markers.
     *
     * <p>Removed because they are not document content: they occupy context-window
     * space, dilute a chunk's embedding with text that cannot answer a question, and a
     * URL among them can be reproduced by the model as though it were a reference.
     */
    private static final List<String> BOILERPLATE_PATTERNS = List.of(
            "(?m)^\\d{1,2}/\\d{1,2}/\\d{2,4},\\s*\\d{1,2}:\\d{2}\\s*[AaPp][Mm].*$",
            "https?://\\S+",
            "(?m)^\\s*\\d+\\s*/\\s*\\d+\\s*$"
    );

    /**
     * Extracts text page by page and returns it alongside a map of page start offsets.
     *
     * <p>Pages are concatenated rather than chunked individually, so that chunking can
     * still span a page boundary and keep its overlap intact. Pages left empty after
     * artefact removal are skipped and contribute no offset.
     *
     * @param pdfBytes raw PDF bytes
     * @param fileName file name, used for logging and error messages
     * @return the combined text and its page map
     * @throws DocumentProcessingException if the PDF cannot be parsed or contains no text
     */
    public ExtractedDocument extractPages(byte[] pdfBytes, String fileName) {
        log.info("Extracting text from '{}' ({} bytes)", fileName, pdfBytes.length);

        // try-with-resources releases PDFBox's buffers and temporary files.
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            int pageCount = document.getNumberOfPages();
            PDFTextStripper stripper = new PDFTextStripper();

            StringBuilder combined = new StringBuilder();
            List<PageSpan> pageSpans = new ArrayList<>();
            int rawLength = 0;

            for (int page = 1; page <= pageCount; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);

                String rawPage = stripper.getText(document);
                rawLength += rawPage.length();

                String pageText = TextNormaliser.normalise(removeBoilerplate(rawPage));
                if (pageText.isEmpty()) {
                    continue;
                }

                if (combined.length() > 0) {
                    combined.append("\n\n");
                }
                pageSpans.add(new PageSpan(combined.length(), page));
                combined.append(pageText);
            }

            String text = combined.toString();
            if (text.isBlank()) {
                throw new DocumentProcessingException(
                        "No text could be extracted from '" + fileName + "'. "
                                + "The PDF may be image-based, in which case OCR is required.");
            }

            log.info("Extracted '{}': {} pages, {} raw characters, {} after cleaning",
                    fileName, pageCount, rawLength, text.length());

            return new ExtractedDocument(text, pageSpans, pageCount);

        } catch (IOException e) {
            throw new DocumentProcessingException(
                    "Failed to parse PDF file '" + fileName + "': " + e.getMessage(), e);
        }
    }

    private String removeBoilerplate(String text) {
        String cleaned = text;
        for (String pattern : BOILERPLATE_PATTERNS) {
            cleaned = cleaned.replaceAll(pattern, "");
        }
        return cleaned;
    }

    /**
     * Marks the character offset in the combined text at which a page begins.
     *
     * @param startOffset inclusive offset of the page's first character
     * @param pageNumber  one-based page number in the source PDF
     */
    public record PageSpan(int startOffset, int pageNumber) {}

    /**
     * Extracted text together with the information needed to map a character offset
     * back to a page in the source document.
     *
     * @param text      combined, cleaned text of all non-empty pages
     * @param pageSpans page start offsets in ascending order
     * @param pageCount number of pages in the source PDF, including empty ones
     */
    public record ExtractedDocument(String text, List<PageSpan> pageSpans, int pageCount) {

        /**
         * Returns the one-based page number containing the given character offset,
         * or {@code 1} when no page map is available.
         */
        public int pageOf(int charOffset) {
            int page = pageSpans.isEmpty() ? 1 : pageSpans.get(0).pageNumber();
            for (PageSpan span : pageSpans) {
                if (span.startOffset() > charOffset) {
                    break;
                }
                page = span.pageNumber();
            }
            return page;
        }
    }
}
