package com.project.GradeWalletLedger.service;

import com.fasterxml.jackson.databind.JsonNode;

public interface StatementQuestionInterpreter {
    JsonNode interpret(String question);

    default RiskExplanation explainRisk(String ruleName, JsonNode context) {
        return new RiskExplanation(
                "Rule " + ruleName + " matched the transfer. Review the recent wallet activity before releasing it.",
                "review");
    }

    record RiskExplanation(String explanation, String label) {
    }
}