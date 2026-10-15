package com.example.hrbot.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exposes a {@link ChatClient} bean.
 *
 * <p>Spring AI auto-configures a {@link ChatClient.Builder} but not a built client, so
 * that applications can apply defaults — system prompts, advisors — before building.
 */
@Configuration
public class ChatClientConfig {

    @Bean
    ChatClient chatClient(ChatClient.Builder builder) {
        return builder.build();
    }
}