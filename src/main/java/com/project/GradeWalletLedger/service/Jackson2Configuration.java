package com.project.GradeWalletLedger.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class Jackson2Configuration {
    @Bean
    ObjectMapper walletObjectMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }
}