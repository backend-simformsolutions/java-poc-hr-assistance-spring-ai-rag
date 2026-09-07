package com.example.hrbot.controller;

import com.example.hrbot.dto.ChatRequest;
import com.example.hrbot.dto.ChatResponse;
import com.example.hrbot.rag.ChatService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Question-answering endpoint. Delegates the RAG pipeline to {@link ChatService}.
 */
@Slf4j
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    /**
     * Answers a question from the ingested policy documents.
     *
     * <p>A constraint violation on the request body raises
     * {@code MethodArgumentNotValidException}, which {@code GlobalExceptionHandler}
     * converts into a 400 response.
     *
     * @param request the question and an optional {@code topK} override
     * @return the answer with its citations and retrieval metadata
     */
    @PostMapping
    public ResponseEntity<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        log.info("Chat request received: question='{}', topK={}",
                request.question(),
                request.requestedTopK() == null ? "default (from config)" : request.requestedTopK());

        ChatResponse response = chatService.chat(request);

        log.info("Chat response generated in {}ms, {} chunks used",
                response.processingTimeMs(), response.chunksRetrieved());

        return ResponseEntity.ok(response);
    }
}