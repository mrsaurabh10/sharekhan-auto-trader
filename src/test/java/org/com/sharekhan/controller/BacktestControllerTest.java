package org.com.sharekhan.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.com.sharekhan.dto.backtest.BacktestDailyReplayRangeRunResponse;
import org.com.sharekhan.dto.backtest.BacktestReportRequest;
import org.com.sharekhan.dto.backtest.BacktestReportResponse;
import org.com.sharekhan.dto.backtest.BacktestReplayRequest;
import org.com.sharekhan.dto.backtest.BacktestReplayResponse;
import org.com.sharekhan.service.BacktestDailyReplayService;
import org.com.sharekhan.service.BacktestReportService;
import org.com.sharekhan.service.BacktestReplayService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BacktestControllerTest {

    @Test
    void rejectsMissingAdminTokenWhenConfigured() throws Exception {
        BacktestReplayService service = mock(BacktestReplayService.class);
        BacktestController controller = controller(service, mock(BacktestDailyReplayService.class), mock(BacktestReportService.class));
        ReflectionTestUtils.setField(controller, "adminToken", "secret-token");

        mockMvc(controller).perform(post("/api/backtests/trade/1/replay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status", is("error")));
    }

    @Test
    void returnsReplayResponseWhenAdminTokenIsValid() throws Exception {
        BacktestReplayService service = mock(BacktestReplayService.class);
        BacktestController controller = controller(service, mock(BacktestDailyReplayService.class), mock(BacktestReportService.class));
        ReflectionTestUtils.setField(controller, "adminToken", "secret-token");

        when(service.replayTrade(eq(1L), org.mockito.ArgumentMatchers.any(BacktestReplayRequest.class)))
                .thenReturn(BacktestReplayResponse.builder()
                        .status("success")
                        .tradeSetupId(1L)
                        .message("Replay completed")
                        .build());

        mockMvc(controller).perform(post("/api/backtests/trade/1/replay")
                        .header("X-Admin-Token", "secret-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("success")))
                .andExpect(jsonPath("$.tradeSetupId", is(1)));
    }

    @Test
    void returnsRangeReplayResponseWhenAdminTokenIsValid() throws Exception {
        BacktestDailyReplayService dailyReplayService = mock(BacktestDailyReplayService.class);
        BacktestController controller = controller(mock(BacktestReplayService.class), dailyReplayService, mock(BacktestReportService.class));
        ReflectionTestUtils.setField(controller, "adminToken", "secret-token");

        when(dailyReplayService.startDateRange(eq(LocalDate.of(2026, 6, 8)), eq(LocalDate.of(2026, 6, 17)), eq(false)))
                .thenReturn(BacktestDailyReplayRangeRunResponse.builder()
                        .status("RUNNING")
                        .runId("range-1")
                        .from(LocalDate.of(2026, 6, 8))
                        .to(LocalDate.of(2026, 6, 17))
                        .build());

        mockMvc(controller).perform(post("/api/backtests/atr-signal/daily-replay/range")
                        .header("X-Admin-Token", "secret-token")
                        .param("from", "2026-06-08")
                        .param("to", "2026-06-17"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("RUNNING")))
                .andExpect(jsonPath("$.runId", is("range-1")));
    }

    @Test
    void returnsRangeReplayStatusWhenAdminTokenIsValid() throws Exception {
        BacktestDailyReplayService dailyReplayService = mock(BacktestDailyReplayService.class);
        BacktestController controller = controller(mock(BacktestReplayService.class), dailyReplayService, mock(BacktestReportService.class));
        ReflectionTestUtils.setField(controller, "adminToken", "secret-token");

        when(dailyReplayService.rangeStatus("range-1"))
                .thenReturn(BacktestDailyReplayRangeRunResponse.builder()
                        .status("SUCCESS")
                        .runId("range-1")
                        .tradeCount(100)
                        .build());

        mockMvc(controller).perform(get("/api/backtests/atr-signal/daily-replay/range/range-1")
                        .header("X-Admin-Token", "secret-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("SUCCESS")))
                .andExpect(jsonPath("$.runId", is("range-1")))
                .andExpect(jsonPath("$.tradeCount", is(100)));
    }

    @Test
    void returnsReportResponseWhenAdminTokenIsValid() throws Exception {
        BacktestReportService reportService = mock(BacktestReportService.class);
        BacktestController controller = controller(mock(BacktestReplayService.class), mock(BacktestDailyReplayService.class), reportService);
        ReflectionTestUtils.setField(controller, "adminToken", "secret-token");

        when(reportService.startReport(org.mockito.ArgumentMatchers.any(BacktestReportRequest.class)))
                .thenReturn(BacktestReportResponse.builder()
                        .status("RUNNING")
                        .reportId("report-1")
                        .downloadUrl("/api/backtests/reports/report-1/download")
                        .tradeCount(2)
                        .resultCount(4)
                        .build());

        mockMvc(controller).perform(post("/api/backtests/reports")
                        .header("X-Admin-Token", "secret-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("RUNNING")))
                .andExpect(jsonPath("$.reportId", is("report-1")))
                .andExpect(jsonPath("$.downloadUrl", is("/api/backtests/reports/report-1/download")));
    }

    @Test
    void returnsReportStatusWhenAdminTokenIsValid() throws Exception {
        BacktestReportService reportService = mock(BacktestReportService.class);
        BacktestController controller = controller(mock(BacktestReplayService.class), mock(BacktestDailyReplayService.class), reportService);
        ReflectionTestUtils.setField(controller, "adminToken", "secret-token");

        when(reportService.reportStatus("report-1"))
                .thenReturn(BacktestReportResponse.builder()
                        .status("SUCCESS")
                        .reportId("report-1")
                        .downloadUrl("/api/backtests/reports/report-1/download")
                        .build());

        mockMvc(controller).perform(get("/api/backtests/reports/report-1")
                        .header("X-Admin-Token", "secret-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("SUCCESS")))
                .andExpect(jsonPath("$.reportId", is("report-1")));
    }

    private BacktestController controller(BacktestReplayService replayService,
                                          BacktestDailyReplayService dailyReplayService,
                                          BacktestReportService reportService) {
        return new BacktestController(replayService, dailyReplayService, reportService);
    }

    private MockMvc mockMvc(BacktestController controller) {
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }
}
