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
    void pdfDownloadEnforcesSessionScopeAndPreservesAllFilters() throws Exception {
        var service = mock(TradeAnalyticsService.class);
        var currentUser = mock(CurrentUserService.class);
        var pdf = mock(org.com.sharekhan.service.TradeAnalyticsPdfService.class);
        when(currentUser.scopedUserId(99L)).thenReturn(7L);
        var day = LocalDate.of(2026, 11, 25);
        var report = new org.com.sharekhan.service.TradeAnalyticsReportData(
                TradeAnalyticsResponse.builder().filters(TradeAnalyticsResponse.Filters.builder().from(day).to(day).build()).build(), List.of());
        when(service.getReportData(7L, day, day, "NIFTY", List.of("alpha", "beta"), 11L, true, "own")).thenReturn(report);
        when(pdf.generate(report)).thenReturn("%PDF-test".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        var mvc = MockMvcBuilders.standaloneSetup(new TradeAnalyticsController(service,
                mock(GeminiTradeInsightService.class), currentUser, pdf)).build();
        mvc.perform(get("/api/analytics/trades/report.pdf").param("userId", "99").param("scope", "simulator")
                        .param("from", day.toString()).param("to", day.toString()).param("symbol", "NIFTY")
                        .param("source", " alpha,beta ", "alpha").param("brokerCredentialsId", "11").param("intraday", "true"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().contentType("application/pdf"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control", "no-store"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Content-Disposition",
                        "attachment; filename=\"trade-analytics-2026-11-25-to-2026-11-25.pdf\""));
    }

    @Test
    void returnsTradeAnalyticsResponseForQueryParams() throws Exception {
        TradeAnalyticsService service = mock(TradeAnalyticsService.class);
        GeminiTradeInsightService geminiTradeInsightService = mock(GeminiTradeInsightService.class);
        CurrentUserService currentUserService = mock(CurrentUserService.class);
        when(currentUserService.scopedUserId(7L)).thenReturn(7L);
        TradeAnalyticsController controller = new TradeAnalyticsController(service, geminiTradeInsightService, currentUserService, new org.com.sharekhan.service.TradeAnalyticsPdfService());
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
                eq(true),
                eq("own")
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
        TradeAnalyticsController controller = new TradeAnalyticsController(service, geminiTradeInsightService, currentUserService, new org.com.sharekhan.service.TradeAnalyticsPdfService());
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

        when(service.getTradeAnalytics(eq(7L), eq(null), eq(null), eq(null), eq(null), eq(null), eq(null), eq("own")))
                .thenReturn(response);
        when(geminiTradeInsightService.addNarrative(response)).thenReturn(responseWithAi);

        mockMvc.perform(get("/api/analytics/trades")
                        .param("userId", "7")
                        .param("ai", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiNarrative", is("Gemini says size down losing symbols.")));
    }

    @Test
    void adminOwnScopeStaysUserOnlyAnalyticsScope() throws Exception {
        TradeAnalyticsService service = mock(TradeAnalyticsService.class);
        GeminiTradeInsightService geminiTradeInsightService = mock(GeminiTradeInsightService.class);
        CurrentUserService currentUserService = mock(CurrentUserService.class);
        when(currentUserService.scopedUserId(3L)).thenReturn(3L);
        when(currentUserService.isAdmin()).thenReturn(true);
        TradeAnalyticsController controller = new TradeAnalyticsController(service, geminiTradeInsightService, currentUserService, new org.com.sharekhan.service.TradeAnalyticsPdfService());
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
                        .scope("own")
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
                eq("own")
        )).thenReturn(response);

        mockMvc.perform(get("/api/analytics/trades")
                        .param("userId", "3")
                        .param("scope", "own")
                        .param("from", "2026-04-12")
                        .param("to", "2026-05-12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filters.scope", is("own")))
                .andExpect(jsonPath("$.summary.realizedPnl", is(500.0)));
    }

    @Test
    void returnsAnalyticsSourcesForScopedUserAndScope() throws Exception {
        TradeAnalyticsService service = mock(TradeAnalyticsService.class);
        GeminiTradeInsightService geminiTradeInsightService = mock(GeminiTradeInsightService.class);
        CurrentUserService currentUserService = mock(CurrentUserService.class);
        when(currentUserService.scopedUserId(7L)).thenReturn(7L);
        when(service.getAvailableSources(eq(7L), eq("own"))).thenReturn(List.of("api", "telegram"));
        TradeAnalyticsController controller = new TradeAnalyticsController(service, geminiTradeInsightService, currentUserService, new org.com.sharekhan.service.TradeAnalyticsPdfService());
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();

        mockMvc.perform(get("/api/analytics/sources")
                        .param("userId", "7")
                        .param("scope", "OWN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]", is("api")))
                .andExpect(jsonPath("$[1]", is("telegram")));
    }

    @Test
    void acceptsMultipleSourceFilters() throws Exception {
        TradeAnalyticsService service = mock(TradeAnalyticsService.class);
        GeminiTradeInsightService geminiTradeInsightService = mock(GeminiTradeInsightService.class);
        CurrentUserService currentUserService = mock(CurrentUserService.class);
        when(currentUserService.scopedUserId(7L)).thenReturn(7L);
        TradeAnalyticsController controller = new TradeAnalyticsController(service, geminiTradeInsightService, currentUserService, new org.com.sharekhan.service.TradeAnalyticsPdfService());
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();

        TradeAnalyticsResponse response = TradeAnalyticsResponse.builder()
                .filters(TradeAnalyticsResponse.Filters.builder()
                        .userId(7L)
                        .source("telegram,admin-ui")
                        .sources(List.of("telegram", "admin-ui"))
                        .scope("own")
                        .build())
                .summary(TradeAnalyticsResponse.Summary.builder().realizedPnl(300.0).totalClosedTrades(2).build())
                .bySymbol(List.of())
                .byDay(List.of())
                .recentClosedTrades(List.of())
                .build();
        when(service.getTradeAnalyticsForSources(
                eq(7L),
                eq(null),
                eq(null),
                eq(null),
                eq(List.of("telegram", "admin-ui")),
                eq(null),
                eq(null),
                eq("own")
        )).thenReturn(response);

        mockMvc.perform(get("/api/analytics/trades")
                        .param("userId", "7")
                        .param("scope", "own")
                        .param("source", "telegram")
                        .param("source", "admin-ui"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filters.source", is("telegram,admin-ui")))
                .andExpect(jsonPath("$.filters.sources[0]", is("telegram")))
                .andExpect(jsonPath("$.filters.sources[1]", is("admin-ui")))
                .andExpect(jsonPath("$.summary.totalClosedTrades", is(2)));
    }
}
