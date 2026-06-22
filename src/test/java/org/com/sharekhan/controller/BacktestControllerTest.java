package org.com.sharekhan.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.com.sharekhan.dto.backtest.BacktestDailyReplayRangeRunResponse;
import org.com.sharekhan.dto.backtest.BacktestReplayRequest;
import org.com.sharekhan.dto.backtest.BacktestReplayResponse;
import org.com.sharekhan.service.BacktestDailyReplayService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BacktestControllerTest {

    @Test
    void rejectsMissingAdminTokenWhenConfigured() throws Exception {
        BacktestReplayService service = mock(BacktestReplayService.class);
        BacktestController controller = new BacktestController(service, mock(BacktestDailyReplayService.class));
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
        BacktestController controller = new BacktestController(service, mock(BacktestDailyReplayService.class));
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
        BacktestController controller = new BacktestController(mock(BacktestReplayService.class), dailyReplayService);
        ReflectionTestUtils.setField(controller, "adminToken", "secret-token");

        when(dailyReplayService.runForDateRange(eq(LocalDate.of(2026, 6, 8)), eq(LocalDate.of(2026, 6, 17))))
                .thenReturn(BacktestDailyReplayRangeRunResponse.builder()
                        .status("success")
                        .from(LocalDate.of(2026, 6, 8))
                        .to(LocalDate.of(2026, 6, 17))
                        .dayCount(8)
                        .tradeCount(100)
                        .resultCount(200)
                        .successCount(190)
                        .errorCount(10)
                        .build());

        mockMvc(controller).perform(post("/api/backtests/atr-signal/daily-replay/range")
                        .header("X-Admin-Token", "secret-token")
                        .param("from", "2026-06-08")
                        .param("to", "2026-06-17"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("success")))
                .andExpect(jsonPath("$.dayCount", is(8)))
                .andExpect(jsonPath("$.tradeCount", is(100)));
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
