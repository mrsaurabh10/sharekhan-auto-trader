package org.com.sharekhan.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.com.sharekhan.config.GeminiProperties;
import org.com.sharekhan.dto.TradeAnalyticsResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class GeminiTradeInsightService {

    private static final Logger log = LoggerFactory.getLogger(GeminiTradeInsightService.class);

    private final GeminiProperties properties;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate = new RestTemplate();

    public TradeAnalyticsResponse addNarrative(TradeAnalyticsResponse report) {
        if (!properties.isEnabled()) {
            return withNarrative(report, "Gemini analysis is disabled.");
        }
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            return withNarrative(report, "Gemini API key is not configured. Set GEMINI_API_KEY to enable AI commentary.");
        }

        try {
            String prompt = buildPrompt(report);
            String url = properties.getBaseUrl()
                    + "/models/"
                    + properties.getModel()
                    + ":generateContent";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("x-goog-api-key", properties.getApiKey());

            Map<String, Object> generationConfig = new LinkedHashMap<>();
            generationConfig.put("temperature", 0.2);
            generationConfig.put("maxOutputTokens", properties.getMaxOutputTokens());
            if (properties.getThinkingBudget() != null) {
                generationConfig.put("thinkingConfig", Map.of("thinkingBudget", properties.getThinkingBudget()));
            }

            Map<String, Object> payload = Map.of(
                    "contents", List.of(Map.of(
                            "role", "user",
                            "parts", List.of(Map.of("text", prompt))
                    )),
                    "generationConfig", generationConfig
            );

            ResponseEntity<String> response = restTemplate.postForEntity(
                    url,
                    new HttpEntity<>(payload, headers),
                    String.class
            );

            return withNarrative(report, extractText(response.getBody()));
        } catch (Exception ex) {
            log.warn("Gemini trade analysis failed: {}", ex.getMessage());
            return withNarrative(report, "Gemini analysis failed: " + ex.getMessage());
        }
    }

    private String buildPrompt(TradeAnalyticsResponse report) throws Exception {
        Map<String, Object> promptData = new LinkedHashMap<>();
        promptData.put("filters", report.getFilters());
        promptData.put("summary", report.getSummary());
        promptData.put("topSymbols", report.getBySymbol());
        promptData.put("dailyPnl", report.getByDay());
        promptData.put("recentClosedTrades", report.getRecentClosedTrades());
        String reportJson = objectMapper.writeValueAsString(promptData);

        return """
                You are helping an Indian intraday/options trader review executed trade history.
                Use only the JSON metrics below. Do not invent market context or future predictions.
                Return:
                1. A concise diagnosis of performance.
                2. The top 3 issues to investigate.
                3. 3 concrete next actions for improving the trading setup.
                Keep it practical and risk-aware. Complete the response in 350-600 words.

                Trade analysis JSON:
                %s
                """.formatted(reportJson);
    }

    private String extractText(String responseBody) throws Exception {
        if (responseBody == null || responseBody.isBlank()) {
            return "Gemini returned an empty response.";
        }
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode parts = root.path("candidates").path(0).path("content").path("parts");
        if (!parts.isArray() || parts.isEmpty()) {
            return "Gemini did not return text content.";
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode part : parts) {
            String value = part.path("text").asText("");
            if (!value.isBlank()) {
                if (!text.isEmpty()) {
                    text.append("\n");
                }
                text.append(value);
            }
        }
        return text.isEmpty() ? "Gemini did not return text content." : text.toString();
    }

    private TradeAnalyticsResponse withNarrative(TradeAnalyticsResponse report, String narrative) {
        report.setAiNarrative(narrative);
        return report;
    }
}
