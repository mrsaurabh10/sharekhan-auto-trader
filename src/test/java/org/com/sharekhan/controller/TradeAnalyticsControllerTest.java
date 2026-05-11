package org.com.sharekhan.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.com.sharekhan.dto.TradeAnalyticsResponse;
import org.com.sharekhan.service.CurrentUserService;
import org.com.sharekhan.service.GeminiTradeInsightService;
import org.com.sharekhan.service.TradeAnalyticsService;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.util.List;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TradeAnalyticsControllerTest {
    @Test
    void returnsTradeAnalyticsResponseForQueryParams() throws Exception {
        TradeAnalyticsService service = mock(TradeAnalyticsService.class);
        GeminiTradeInsightService geminiTradeInsightService = mock(GeminiTradeInsightService.class);
        CurrentUserService currentUserService = mock(CurrentUserService.class);
        when(currentUserService.scopedUserId(7L)).thenReturn(7L);
        TradeAnalyticsController controller = new TradeAnalyticsController(service, geminiTradeInsightService, currentUserService);
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();

        TradeAnalyticsResponse response = TradeAnalyticsResponse.builder()
                .filters(TradeAnalyticsResponse.Filters.builder()
                        .userId(7L)
                        .from(LocalDate.of(2026, 4, 1))
                        .to(LocalDate.of(2026, 4, 30))
                        .symbol("NIFTY")
                        .source("telegram")
                        .brokerCredentialsId(11L)
                        .intraday(true)
                        .build())
                .summary(TradeAnalyticsResponse.Summary.builder()
                        .realizedPnl(250.0)
                        .totalClosedTrades(2)
                        .winningTrades(1)
                        .losingTrades(1)
                        .breakevenTrades(0)
                        .winRate(50.0)
                        .lossRate(50.0)
                        .profitFactor(1.5)
                        .openTrades(1)
                        .openQuantity(75L)
                        .rejectedTrades(0)
                        .failedTrades(0)
                        .build())
                .bySymbol(List.of())
                .byDay(List.of())
                .recentClosedTrades(List.of())
                .build();
        when(service.getTradeAnalytics(
                eq(7L),
                eq(LocalDate.of(2026, 4, 1)),
                eq(LocalDate.of(2026, 4, 30)),
                eq("NIFTY"),
                eq("telegram"),
                eq(11L),
                eq(true)
        )).thenReturn(response);

        mockMvc.perform(get("/api/analytics/trades")
                        .param("userId", "7")
                        .param("from", "2026-04-01")
                        .param("to", "2026-04-30")
                        .param("symbol", "NIFTY")
                        .param("source", "telegram")
                        .param("brokerCredentialsId", "11")
                        .param("intraday", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filters.userId", is(7)))
                .andExpect(jsonPath("$.filters.from", is("2026-04-01")))
                .andExpect(jsonPath("$.filters.symbol", is("NIFTY")))
                .andExpect(jsonPath("$.filters.source", is("telegram")))
                .andExpect(jsonPath("$.summary.realizedPnl", is(250.0)))
                .andExpect(jsonPath("$.summary.winRate", is(50.0)))
                .andExpect(jsonPath("$.bySymbol").isArray())
                .andExpect(jsonPath("$.byDay").isArray())
                .andExpect(jsonPath("$.recentClosedTrades").isArray());
    }

    @Test
    void addsGeminiNarrativeWhenAiFlagIsTrue() throws Exception {
        TradeAnalyticsService service = mock(TradeAnalyticsService.class);
        GeminiTradeInsightService geminiTradeInsightService = mock(GeminiTradeInsightService.class);
        CurrentUserService currentUserService = mock(CurrentUserService.class);
        when(currentUserService.scopedUserId(7L)).thenReturn(7L);
        TradeAnalyticsController controller = new TradeAnalyticsController(service, geminiTradeInsightService, currentUserService);
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();

        TradeAnalyticsResponse response = TradeAnalyticsResponse.builder()
                .summary(TradeAnalyticsResponse.Summary.builder().realizedPnl(100.0).build())
                .bySymbol(List.of())
                .byDay(List.of())
                .recentClosedTrades(List.of())
                .build();
        TradeAnalyticsResponse responseWithAi = TradeAnalyticsResponse.builder()
                .summary(TradeAnalyticsResponse.Summary.builder().realizedPnl(100.0).build())
                .bySymbol(List.of())
                .byDay(List.of())
                .recentClosedTrades(List.of())
                .aiNarrative("Gemini says size down losing symbols.")
                .build();

        when(service.getTradeAnalytics(eq(7L), eq(null), eq(null), eq(null), eq(null), eq(null), eq(null)))
                .thenReturn(response);
        when(geminiTradeInsightService.addNarrative(response)).thenReturn(responseWithAi);

        mockMvc.perform(get("/api/analytics/trades")
                        .param("userId", "7")
                        .param("ai", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiNarrative", is("Gemini says size down losing symbols.")));
    }

    @Test
    void adminOwnScopeUsesAllAnalyticsScope() throws Exception {
        TradeAnalyticsService service = mock(TradeAnalyticsService.class);
        GeminiTradeInsightService geminiTradeInsightService = mock(GeminiTradeInsightService.class);
        CurrentUserService currentUserService = mock(CurrentUserService.class);
        when(currentUserService.scopedUserId(3L)).thenReturn(3L);
        when(currentUserService.isAdmin()).thenReturn(true);
        TradeAnalyticsController controller = new TradeAnalyticsController(service, geminiTradeInsightService, currentUserService);
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();

        TradeAnalyticsResponse response = TradeAnalyticsResponse.builder()
                .filters(TradeAnalyticsResponse.Filters.builder()
                        .userId(3L)
                        .from(LocalDate.of(2026, 4, 12))
                        .to(LocalDate.of(2026, 5, 12))
                        .scope("all")
                        .build())
                .summary(TradeAnalyticsResponse.Summary.builder()
                        .realizedPnl(500.0)
                        .totalClosedTrades(1)
                        .build())
                .bySymbol(List.of())
                .byDay(List.of())
                .recentClosedTrades(List.of())
                .build();
        when(service.getTradeAnalytics(
                eq(3L),
                eq(LocalDate.of(2026, 4, 12)),
                eq(LocalDate.of(2026, 5, 12)),
                eq(null),
                eq(null),
                eq(null),
                eq(null),
                eq("all")
        )).thenReturn(response);

        mockMvc.perform(get("/api/analytics/trades")
                        .param("userId", "3")
                        .param("scope", "own")
                        .param("from", "2026-04-12")
                        .param("to", "2026-05-12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filters.scope", is("all")))
                .andExpect(jsonPath("$.summary.realizedPnl", is(500.0)));
    }
}
