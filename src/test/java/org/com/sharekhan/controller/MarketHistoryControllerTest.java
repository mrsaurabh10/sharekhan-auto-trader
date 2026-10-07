package org.com.sharekhan.controller;

import org.com.sharekhan.entity.MarketHistoryCandleEntity;
import org.com.sharekhan.strategy.MarketHistoryArchiveService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.*;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MarketHistoryControllerTest {
    @Test
    void exportRequiresTokenAndProducesCompatibleCsv() throws Exception {
        MarketHistoryArchiveService archive = mock(MarketHistoryArchiveService.class);
        MarketHistoryController controller = new MarketHistoryController(archive);
        ReflectionTestUtils.setField(controller, "adminToken", "test-token");
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        String url = "/api/backtests/history/export?symbol=NIFTY&from=2026-10-06&to=2026-10-06";
        mvc.perform(get(url)).andExpect(status().isForbidden());
        verifyNoInteractions(archive);
        LocalDate date = LocalDate.of(2026, 10, 6);
        when(archive.read("NIFTY", date, date)).thenReturn(List.of(MarketHistoryCandleEntity.builder()
                .candleTime(date.atTime(9, 15)).openPrice(100).highPrice(102).lowPrice(98).closePrice(101).build()));
        mvc.perform(get(url).header("X-Admin-Token", "test-token"))
                .andExpect(status().isOk())
                .andExpect(content().string("date,open,high,low,close,volume\n2026-10-06 09:15:00,100.0,102.0,98.0,101.0,0\n"));
    }
}
