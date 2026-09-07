package com.example.hrbot.service;

import com.example.hrbot.dto.UploadResponse;
import com.example.hrbot.exception.DocumentProcessingException;
import com.example.hrbot.ingestion.PdfTextExtractor;
import com.example.hrbot.ingestion.TextChunkingService;
import com.example.hrbot.ingestion.TextChunkingService.TextChunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates document ingestion:
 * <pre>
 *   PDF -> text -> chunks -> embeddings -> vector store
 * </pre>
 *
 * <p>Each step is delegated: {@link PdfTextExtractor} for extraction,
 * {@link TextChunkingService} for splitting, and {@link VectorStore} for embedding and
 * storage. {@code VectorStore.add} embeds each document with the configured
 * {@code EmbeddingModel} before inserting it, so this class never calls the embedding
 * model directly.
 */
@Slf4j
@Service
public class DocumentIngestionService {

    private static final long MAX_FILE_SIZE_BYTES = 50L * 1024 * 1024;

    private final PdfTextExtractor pdfTextExtractor;
    private final TextChunkingService textChunkingService;
    private final VectorStore vectorStore;

    public DocumentIngestionService(
            PdfTextExtractor pdfTextExtractor,
            TextChunkingService textChunkingService,
            VectorStore vectorStore) {
        this.pdfTextExtractor = pdfTextExtractor;
        this.textChunkingService = textChunkingService;
        this.vectorStore = vectorStore;
    }

    /**
     * Runs an uploaded PDF through the ingestion pipeline.
     *
     * @param file the uploaded PDF
     * @return the file name and ingestion statistics
     * @throws DocumentProcessingException if the file is rejected, cannot be parsed, or
     *                                     yields no chunks
     */
    public UploadResponse ingestDocument(MultipartFile file) {
        long startTime = System.currentTimeMillis();
        String fileName = file.getOriginalFilename();

        log.info("Starting ingestion pipeline for: {}", fileName);

        validateFile(file);

        try {
            // Extraction records the offset at which each page begins, which is what
            // allows every chunk to carry a verifiable page number.
            byte[] pdfBytes = file.getBytes();
            PdfTextExtractor.ExtractedDocument extracted = pdfTextExtractor.extractPages(pdfBytes, fileName);
            String extractedText = extracted.text();

            List<TextChunk> chunks = textChunkingService.chunkText(extractedText, fileName);

            if (chunks.isEmpty()) {
                throw new DocumentProcessingException(
                        "No chunks could be created from '" + fileName + "'. The document might be too short.");
            }

            List<Document> documents = chunks.stream()
                    .map(chunk -> new Document(
                            chunk.content(),
                            Map.of(
                                    "documentName", chunk.documentName(),
                                    "chunkIndex", chunk.chunkIndex(),
                                    "totalChunks", chunks.size(),
                                    "contentLength", chunk.content().length(),
                                    // Page range backing user-facing citations. A range rather
                                    // than a single page, because a chunk may span a page break;
                                    // for chunks within one page both values are equal.
                                    "pageNumber", extracted.pageOf(chunk.startOffset()),
                                    "pageNumberEnd", extracted.pageOf(Math.max(chunk.startOffset(),
                                                                              chunk.endOffset() - 1))
                            )
                    ))
                    .toList();

            log.info("Created {} Spring AI Document objects from '{}'", documents.size(), fileName);

            // Embeds every document and inserts it. Each embedding is a separate HTTP call
            // to Ollama, so ingestion time scales with the chunk count.
            vectorStore.add(documents);

            long processingTime = System.currentTimeMillis() - startTime;

            log.info("Successfully ingested '{}': {} chunks stored in {}ms",
                    fileName, chunks.size(), processingTime);

            return new UploadResponse(
                    fileName,
                    extractedText.length(),
                    chunks.size(),
                    "Document successfully processed and stored in vector database.",
                    processingTime
            );

        } catch (IOException e) {
            throw new DocumentProcessingException(
                    "Failed to read uploaded file '" + fileName + "': " + e.getMessage(), e);
        }
    }

    /**
     * Rejects unsuitable uploads before extraction and embedding, which are the
     * expensive stages.
     */
    private void validateFile(MultipartFile file) {
        String fileName = file.getOriginalFilename();
        long fileSize = file.getSize();

        log.info("Validating file: name='{}', size={} bytes, isEmpty={}",
                fileName, fileSize, file.isEmpty());

        if (file.isEmpty()) {
            log.error("File validation failed: uploaded file is empty (name: {})", fileName);
            throw new DocumentProcessingException(
                    "Uploaded file is empty. File name: " + fileName + ", Size: " + fileSize + " bytes.");
        }

        if (fileName == null || !fileName.toLowerCase().endsWith(".pdf")) {
            log.error("File validation failed: not a PDF file (name: {})", fileName);
            throw new DocumentProcessingException(
                    "Only PDF files are supported. Received: " + fileName);
        }

        if (fileSize > MAX_FILE_SIZE_BYTES) {
            log.error("File validation failed: file too large (size: {} bytes)", fileSize);
            throw new DocumentProcessingException(
                    "File size exceeds maximum allowed size of 50MB. Size: " + fileSize + " bytes.");
        }

        log.info("File validation passed for: {} ({} bytes)", fileName, fileSize);
    }
}