package com.example.hrbot.controller;

import com.example.hrbot.dto.UploadResponse;
import com.example.hrbot.repository.DocumentChunkRepository;
import com.example.hrbot.service.DocumentIngestionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * Document management endpoints: PDF upload and bulk removal.
 */
@Slf4j
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentIngestionService ingestionService;
    private final DocumentChunkRepository chunkRepository;

    public DocumentController(DocumentIngestionService ingestionService,
                              DocumentChunkRepository chunkRepository) {
        this.ingestionService = ingestionService;
        this.chunkRepository = chunkRepository;
    }

    /**
     * Ingests an uploaded PDF: extract, chunk, embed and store.
     *
     * @param file the PDF, sent as the {@code file} part of a multipart request
     * @return the file name and ingestion statistics
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UploadResponse> uploadDocument(
            @RequestParam("file") MultipartFile file) {

        log.info("Received file upload: name='{}', size={} bytes, type='{}'",
                file.getOriginalFilename(),
                file.getSize(),
                file.getContentType());

        UploadResponse response = ingestionService.ingestDocument(file);

        return ResponseEntity.ok(response);
    }

    /**
     * Removes every chunk from the vector store.
     *
     * <p>Chunking configuration is applied at ingestion time, so changing it requires
     * clearing the store and re-uploading the documents.
     *
     * @return the number of chunks deleted
     */
    @DeleteMapping("/all")
    public ResponseEntity<Map<String, Object>> deleteAllDocuments() {
        log.info("Deleting all document chunks from vector store");
        int deleted = chunkRepository.deleteAllChunks();
        return ResponseEntity.ok(Map.of(
                "deletedChunks", deleted,
                "message", "All chunks deleted. You can now re-upload documents."
        ));
    }
}