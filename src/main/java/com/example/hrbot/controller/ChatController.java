package com.example.hrbot.controller;

import com.example.hrbot.dto.ChatRequest;
import com.example.hrbot.dto.ChatResponse;
import com.example.hrbot.dto.ChatStreamEvent;
import com.example.hrbot.rag.ChatService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.time.Duration;

/**
 * Question-answering endpoint. Delegates the RAG pipeline to {@link ChatService}.
 */
@Slf4j
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    /**
     * How often a streamed answer writes a keep-alive comment. Short, because it bounds
     * how long a disconnected client can keep the model working.
     */
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(1);

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

    /**
     * Answers a question as a stream of Server-Sent Events, delivering the answer token
     * by token as the model generates it.
     *
     * <p>Events, each with a JSON body: {@code retrieval}, then {@code token} repeatedly,
     * then exactly one of {@code done} or {@code error}. See {@link ChatStreamEvent}.
     *
     * <p>No {@code produces} attribute: returning {@link ServerSentEvent}s is what selects
     * the event-stream format. A request rejected before streaming starts — a validation
     * failure, say — is answered with the usual JSON error body, which
     * {@code GlobalExceptionHandler} writes with an explicit content type so that it
     * reaches clients that accept only {@code text/event-stream}.
     *
     * <p>A {@code :keep-alive} comment is interleaved every {@link #HEARTBEAT_INTERVAL};
     * SSE clients ignore comments. The servlet container learns that a client has gone
     * only when a write to it fails, so without these the app would not notice a
     * disconnect during the silent stretches — while the model reads the prompt, and
     * while the answer is attributed — and would keep the model busy for nobody.
     *
     * @param request the question and an optional {@code topK} override
     * @return the answer as a stream of events
     */
    @PostMapping("/stream")
    public Flux<ServerSentEvent<ChatStreamEvent>> stream(@Valid @RequestBody ChatRequest request) {
        log.info("Streaming chat request received: question='{}', topK={}",
                request.question(),
                request.requestedTopK() == null ? "default (from config)" : request.requestedTopK());

        Flux<ServerSentEvent<ChatStreamEvent>> events = chatService.streamChat(request)
                .map(event -> ServerSentEvent.<ChatStreamEvent>builder(event)
                        .event(eventName(event))
                        .build());

        Flux<ServerSentEvent<ChatStreamEvent>> heartbeats = Flux.interval(HEARTBEAT_INTERVAL)
                .map(tick -> ServerSentEvent.<ChatStreamEvent>builder().comment("keep-alive").build());

        // Every stream ends with done or error, which also stops the heartbeats.
        return Flux.merge(events, heartbeats)
                .takeUntil(event -> event.data() instanceof ChatStreamEvent.Done
                        || event.data() instanceof ChatStreamEvent.Error);
    }

    private static String eventName(ChatStreamEvent event) {
        return switch (event) {
            case ChatStreamEvent.Retrieval retrieval -> "retrieval";
            case ChatStreamEvent.Token token -> "token";
            case ChatStreamEvent.Done done -> "done";
            case ChatStreamEvent.Error error -> "error";
        };
    }
}