package com.example.hrbot.controller;

import com.example.hrbot.dto.ChatRequest;
import com.example.hrbot.dto.ChatStreamEvent;
import com.example.hrbot.rag.ChatService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies how a streamed answer is framed on the wire: the media type, the event names,
 * and that a rejected request is still answered with the usual JSON error body.
 */
@WebMvcTest(ChatController.class)
class ChatControllerStreamTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChatService chatService;

    @Test
    void streamsEachEventAsNamedServerSentEventWithJsonData() throws Exception {
        when(chatService.streamChat(any(ChatRequest.class))).thenReturn(Flux.just(
                new ChatStreamEvent.Retrieval("a1b2c3d4", "How many leaves?", 0, List.of()),
                new ChatStreamEvent.Token("You get"),
                new ChatStreamEvent.Token(" 13 leaves."),
                new ChatStreamEvent.Done(List.of("Leave Policy.pdf (page 1)"), 42)));

        MvcResult result = mockMvc.perform(post("/api/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\": \"How many leaves?\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(content().string(containsString("event:retrieval\n")))
                .andExpect(content().string(containsString("\"requestId\":\"a1b2c3d4\"")))
                // The leading space is inside the JSON string, so no SSE parser can drop it.
                .andExpect(content().string(containsString(
                        "event:token\ndata:{\"text\":\" 13 leaves.\"}")))
                .andExpect(content().string(containsString("event:done\n")))
                .andExpect(content().string(containsString(
                        "\"sources\":[\"Leave Policy.pdf (page 1)\"]")));
    }

    /**
     * A silent stretch — the model reading the prompt, or attribution — still writes
     * keep-alive comments, which is how a disconnect is noticed; and they stop with done.
     */
    @Test
    void writesKeepAliveCommentsWhileWaitingAndStopsAtDone() throws Exception {
        when(chatService.streamChat(any(ChatRequest.class))).thenReturn(Flux.concat(
                Flux.just(new ChatStreamEvent.Retrieval("a1b2c3d4", "Q?", 0, List.of())),
                Mono.delay(Duration.ofMillis(2500))
                        .thenReturn(new ChatStreamEvent.Done(List.of(), 2500))));

        MvcResult result = mockMvc.perform(post("/api/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\": \"Q?\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(content().string(containsString(":keep-alive\n")))
                .andExpect(content().string(endsWith("event:done\ndata:{\"sources\":[],\"processingTimeMs\":2500}\n\n")));
    }

    @Test
    void namesTheErrorEvent() throws Exception {
        when(chatService.streamChat(any(ChatRequest.class))).thenReturn(Flux.just(
                new ChatStreamEvent.Retrieval("a1b2c3d4", "Q?", 0, List.of()),
                new ChatStreamEvent.Error("a1b2c3d4", "The answer could not be completed.")));

        MvcResult result = mockMvc.perform(post("/api/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\": \"Q?\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(content().string(containsString("event:error\n")));
    }

    /**
     * Validation runs before the stream exists, so a bad request gets a real 400 with the
     * standard error body — even from a client that accepts only an event stream.
     */
    @Test
    void rejectsABlankQuestionWithTheStandardJsonError() throws Exception {
        mockMvc.perform(post("/api/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"question\": \"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").value("Validation Failed"))
                .andExpect(jsonPath("$.path").value("/api/chat/stream"));

        verify(chatService, never()).streamChat(any());
    }
}
