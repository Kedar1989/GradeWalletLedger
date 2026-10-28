package com.project.GradeWalletLedger.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Month;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MockStatementQuestionInterpreter implements StatementQuestionInterpreter {
    private static final Pattern PERSON_TO = Pattern.compile("\\bto\\s+([\\p{L}][\\p{L} .'-]{0,79}?)(?:\\s+(?:last|this|in|from|between)\\b|\\s*[?!.]|$)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PERSON_FROM = Pattern.compile("\\bfrom\\s+([\\p{L}][\\p{L} .'-]{0,79}?)(?:\\s+(?:last|this|in|between)\\b|\\s*[?!.]|$)",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern MONTH_YEAR = Pattern.compile(
        "\\b(January|February|March|April|May|June|July|August|September|October|November|December)\\s+(20\\d{2})\\b",
        Pattern.CASE_INSENSITIVE);
    private static final Map<String, Month> MONTHS = Map.ofEntries(
        Map.entry("JANUARY", Month.JANUARY), Map.entry("FEBRUARY", Month.FEBRUARY),
        Map.entry("MARCH", Month.MARCH), Map.entry("APRIL", Month.APRIL),
        Map.entry("MAY", Month.MAY), Map.entry("JUNE", Month.JUNE),
        Map.entry("JULY", Month.JULY), Map.entry("AUGUST", Month.AUGUST),
        Map.entry("SEPTEMBER", Month.SEPTEMBER), Map.entry("OCTOBER", Month.OCTOBER),
        Map.entry("NOVEMBER", Month.NOVEMBER), Map.entry("DECEMBER", Month.DECEMBER));

    private final ObjectMapper objectMapper;

    public MockStatementQuestionInterpreter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public JsonNode interpret(String question) {
        String normalized = question.toLowerCase(Locale.ROOT);
        ObjectNode result = objectMapper.createObjectNode();
        if (normalized.contains("largest") || normalized.contains("biggest")) {
            result.put("type", "LARGEST_TRANSFER");
        } else if (normalized.contains("how many") || normalized.contains("number of")
                || normalized.contains("count of")) {
            result.put("type", "TRANSFER_COUNT");
        } else if (normalized.contains("receiv") || normalized.contains("got from")) {
            result.put("type", "TOTAL_RECEIVED");
            String counterparty = personFrom(question, PERSON_FROM);
            if (!counterparty.isBlank()) {
                result.put("counterparty", counterparty);
            }
        } else if (normalized.contains("send") || normalized.contains("sent") || normalized.contains("paid")) {
            result.put("type", "TOTAL_SENT_TO_PERSON");
            result.put("counterparty", personFrom(question, PERSON_TO));
        } else {
            result.put("type", "UNSUPPORTED");
        }

        LocalDate today = LocalDate.now();
        Matcher monthYear = MONTH_YEAR.matcher(question);
        if (monthYear.find()) {
            LocalDate firstOfMonth = LocalDate.of(
                Integer.parseInt(monthYear.group(2)), MONTHS.get(monthYear.group(1).toUpperCase(Locale.ROOT)), 1);
            result.put("from", firstOfMonth.toString());
            result.put("to", firstOfMonth.plusMonths(1).minusDays(1).toString());
        } else if (normalized.contains("last month")) {
            LocalDate firstOfLastMonth = today.withDayOfMonth(1).minusMonths(1);
            result.put("from", firstOfLastMonth.toString());
            result.put("to", today.withDayOfMonth(1).minusDays(1).toString());
        } else if (normalized.contains("this month")) {
            result.put("from", today.withDayOfMonth(1).toString());
            result.put("to", today.toString());
        } else if (normalized.contains("today")) {
            result.put("from", today.toString());
            result.put("to", today.toString());
        }
        return result;
    }

    private String personFrom(String question, Pattern pattern) {
        Matcher matcher = pattern.matcher(question.trim());
        return matcher.find() ? matcher.group(1).trim() : "";
    }
}