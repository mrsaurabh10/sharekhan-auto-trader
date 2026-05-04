package org.com.sharekhan.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.com.sharekhan.dto.TradeAnalyticsResponse;
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
        TradeAnalyticsController controller = new TradeAnalyticsController(service);
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
}
