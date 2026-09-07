package com.example.hrbot.ingestion;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits document text into overlapping chunks for embedding and retrieval.
 *
 * <p>Chunk size drives retrieval precision. An embedding represents the average meaning
 * of the text it was computed from, so a chunk covering one topic matches a question
 * about that topic sharply, while a chunk covering ten matches everything weakly.
 * Consecutive chunks overlap so that a fact straddling a boundary survives intact in at
 * least one of them; overlap duplicates text, so it is kept small relative to the chunk
 * size.
 *
 * <h2>Algorithm: recursive character splitting</h2>
 * Text is divided on a hierarchy of separators, strongest first:
 * <pre>
 *   paragraph  ->  line  ->  sentence  ->  clause  ->  word
 * </pre>
 * Any piece still larger than the target size is split again with the next separator
 * down. The resulting pieces ("atoms") are merged greedily into chunks up to the target
 * size. Two properties follow: a boundary always falls on a separator, so no chunk
 * begins or ends mid-word; and related text stays together whenever it fits, because a
 * paragraph is only broken up when it is too large on its own.
 *
 * <p>Sizing is by characters rather than model tokens, which needs no tokenizer but is
 * only an approximation of a context budget; Spring AI's {@code TokenTextSplitter} is
 * the alternative where that matters.
 *
 * @see PdfTextExtractor the extraction step that precedes this one
 */
@Slf4j
@Service
public class TextChunkingService {

    /**
     * Separators in descending order of semantic strength. Splitting prefers the
     * earliest entry that appears in the text, so paragraph breaks are used before
     * line breaks, line breaks before sentence ends, and so on. The final entry is a
     * plain space, which guarantees that a chunk boundary never falls inside a word.
     */
    private static final String[] SEPARATORS = {"\n\n", "\n", ". ", "; ", ", ", " "};

    private final int chunkSize;
    private final int chunkOverlap;

    /**
     * @param chunkSize    target characters per chunk; 500-1500 suits most prose
     * @param chunkOverlap characters shared between consecutive chunks, typically
     *                     5-20% of {@code chunkSize}
     * @throws IllegalArgumentException if either value is out of range, or the overlap is
     *                                  not smaller than the chunk size
     */
    public TextChunkingService(
            @Value("${app.chunking.chunk-size:1000}") int chunkSize,
            @Value("${app.chunking.chunk-overlap:50}") int chunkOverlap) {

        if (chunkSize <= 0) {
            throw new IllegalArgumentException("Chunk size must be positive, was " + chunkSize);
        }
        if (chunkOverlap < 0) {
            throw new IllegalArgumentException("Chunk overlap must not be negative, was " + chunkOverlap);
        }
        if (chunkOverlap >= chunkSize) {
            throw new IllegalArgumentException(
                    "Chunk overlap (" + chunkOverlap + ") must be less than chunk size (" + chunkSize + ")");
        }

        this.chunkSize = chunkSize;
        this.chunkOverlap = chunkOverlap;

        log.info("TextChunkingService initialised with chunkSize={}, chunkOverlap={}",
                chunkSize, chunkOverlap);
    }

    /**
     * Splits text into overlapping chunks aligned to separator boundaries.
     *
     * <p>Character offsets on each returned chunk refer to the text passed in, which
     * lets callers map a chunk back to a location in the source document. Callers that
     * rely on those offsets must pass text that is already normalised — see
     * {@link #normalise(String)}, which is idempotent for that reason.
     *
     * @param text         document text, normally from {@link PdfTextExtractor}
     * @param documentName source document name, copied onto every chunk
     * @return chunks in document order; empty if the text has no content
     */
    public List<TextChunk> chunkText(String text, String documentName) {
        if (text == null || text.isBlank()) {
            log.warn("No text to chunk for '{}'", documentName);
            return List.of();
        }

        String normalised = normalise(text);
        if (normalised.isEmpty()) {
            log.warn("No content left after normalising text from '{}'", documentName);
            return List.of();
        }

        List<Range> atoms = splitIntoAtoms(normalised, 0, normalised.length(), 0);
        List<TextChunk> chunks = mergeIntoChunks(normalised, atoms, documentName);

        log.info("Chunked '{}': {} characters into {} chunks", documentName, normalised.length(), chunks.size());
        return chunks;
    }

    /**
     * Normalises whitespace so chunk boundaries are predictable.
     *
     * <p>Idempotent by design: applying it to already-normalised text returns that text
     * unchanged. Callers that record character offsets against the output depend on
     * this, because a second normalisation pass that shifted characters would
     * invalidate every stored offset.
     */
    public String normalise(String text) {
        return TextNormaliser.normalise(text);
    }

    /**
     * Recursively divides {@code [start, end)} until every piece either fits the target
     * size or cannot be divided further.
     *
     * <p>Each level tries one separator. A piece that is still too large moves down to
     * the next separator; once the separators are exhausted, an oversized piece is cut
     * at fixed intervals as a last resort. That fallback only applies to text with no
     * spaces at all over more than {@code chunkSize} characters.
     */
    private List<Range> splitIntoAtoms(String text, int start, int end, int separatorLevel) {
        List<Range> atoms = new ArrayList<>();
        if (end <= start) {
            return atoms;
        }

        if (end - start <= chunkSize) {
            atoms.add(new Range(start, end));
            return atoms;
        }

        if (separatorLevel >= SEPARATORS.length) {
            for (int cut = start; cut < end; cut += chunkSize) {
                atoms.add(new Range(cut, Math.min(cut + chunkSize, end)));
            }
            return atoms;
        }

        List<Range> segments = splitOnSeparator(text, start, end, SEPARATORS[separatorLevel]);
        if (segments.size() <= 1) {
            // Separator absent from this span; try a weaker one.
            return splitIntoAtoms(text, start, end, separatorLevel + 1);
        }

        for (Range segment : segments) {
            atoms.addAll(splitIntoAtoms(text, segment.start(), segment.end(), separatorLevel + 1));
        }
        return atoms;
    }

    /**
     * Divides {@code [start, end)} at each occurrence of {@code separator}, keeping the
     * separator attached to the segment it terminates so that concatenating the segments
     * reproduces the input exactly.
     */
    private List<Range> splitOnSeparator(String text, int start, int end, String separator) {
        List<Range> segments = new ArrayList<>();
        int segmentStart = start;

        int index = text.indexOf(separator, segmentStart);
        while (index >= 0 && index + separator.length() <= end) {
            segments.add(new Range(segmentStart, index + separator.length()));
            segmentStart = index + separator.length();
            index = text.indexOf(separator, segmentStart);
        }
        if (segmentStart < end) {
            segments.add(new Range(segmentStart, end));
        }
        return segments;
    }

    /**
     * Merges atoms into chunks, filling each chunk up to the target size and then
     * stepping back by the overlap before starting the next one.
     *
     * <p>The step back is measured in whole atoms, so the overlap also begins on a
     * separator boundary. The loop always advances by at least one atom, which
     * guarantees termination regardless of atom sizes.
     */
    private List<TextChunk> mergeIntoChunks(String text, List<Range> atoms, String documentName) {
        List<TextChunk> chunks = new ArrayList<>();
        int index = 0;

        while (index < atoms.size()) {
            int chunkStart = atoms.get(index).start();
            int lastIncluded = index;

            while (lastIncluded + 1 < atoms.size()
                    && atoms.get(lastIncluded + 1).end() - chunkStart <= chunkSize) {
                lastIncluded++;
            }

            int chunkEnd = atoms.get(lastIncluded).end();

            // Offsets are narrowed to the content they describe. An atom boundary can fall
            // on whitespace — the second newline of a page join, for instance — and an
            // offset pointing there would resolve to the page before the one the content
            // is taken from.
            String span = text.substring(chunkStart, chunkEnd);
            int contentStart = chunkStart + (span.length() - span.stripLeading().length());
            int contentEnd = chunkEnd - (span.length() - span.stripTrailing().length());

            if (contentEnd > contentStart) {
                chunks.add(new TextChunk(
                        text.substring(contentStart, contentEnd),
                        chunks.size(),
                        documentName,
                        contentStart,
                        contentEnd));
            }

            index = nextChunkIndex(atoms, index, lastIncluded, chunkEnd);
        }
        return chunks;
    }

    /**
     * Chooses the atom that begins the next chunk: the earliest atom whose content still
     * falls within {@code chunkOverlap} characters of the current chunk's end, never
     * earlier than one atom past the current chunk's first atom.
     */
    private int nextChunkIndex(List<Range> atoms, int firstIncluded, int lastIncluded, int chunkEnd) {
        int next = lastIncluded + 1;
        if (chunkOverlap == 0 || next >= atoms.size()) {
            return next;
        }

        int candidate = next;
        while (candidate - 1 > firstIncluded && chunkEnd - atoms.get(candidate - 1).start() <= chunkOverlap) {
            candidate--;
        }
        return candidate;
    }

    /** Half-open character range {@code [start, end)} within the normalised text. */
    private record Range(int start, int end) {}

    /**
     * One chunk of a document, ready to be embedded and stored.
     *
     * <p>This is the chunking layer's output type: it carries no notion of embeddings or
     * vector storage, which belong to the layers above it.
     *
     * @param content      chunk text, trimmed
     * @param chunkIndex   zero-based position of this chunk within its document
     * @param documentName source document name
     * @param startOffset  inclusive character offset of this chunk in the normalised text
     * @param endOffset    exclusive character offset of this chunk in the normalised text
     */
    public record TextChunk(
            String content,
            int chunkIndex,
            String documentName,
            int startOffset,
            int endOffset
    ) {}
}
