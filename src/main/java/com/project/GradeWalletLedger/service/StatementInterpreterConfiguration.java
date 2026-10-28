package com.project.GradeWalletLedger.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class StatementInterpreterConfiguration {
    @Bean
    @ConditionalOnProperty(name = "app.statement-llm.enabled", havingValue = "true")
    StatementQuestionInterpreter openAiStatementQuestionInterpreter(
            ObjectMapper objectMapper,
            @Value("${app.statement-llm.api-key}") String apiKey,
            @Value("${app.statement-llm.model:gpt-4o-mini}") String model,
            @Value("${app.statement-llm.endpoint:https://api.openai.com/v1/chat/completions}") String endpoint) {
        if (apiKey.isBlank()) {
            throw new IllegalStateException("STATEMENT_LLM_API_KEY is required when the statement LLM is enabled");
        }
        return new OpenAiStatementQuestionInterpreter(objectMapper, apiKey, model, endpoint);
    }

    @Bean
    @ConditionalOnProperty(name = "app.statement-llm.enabled", havingValue = "false", matchIfMissing = true)
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(StatementQuestionInterpreter.class)
    StatementQuestionInterpreter mockStatementQuestionInterpreter(ObjectMapper objectMapper) {
        return new MockStatementQuestionInterpreter(objectMapper);
    }
}