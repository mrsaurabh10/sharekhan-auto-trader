package org.com.sharekhan.service;

import org.com.sharekhan.repository.ScriptMasterRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StockAtrTradeServiceTest {

    private static final DateTimeFormatter EXPIRY_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    @Test
    void nearestExpiryHonorsRequestedExpiryMonth() {
        ScriptMasterRepository scriptMasterRepository = mock(ScriptMasterRepository.class);
        StockAtrTradeService service = new StockAtrTradeService(
                scriptMasterRepository,
                mock(SharekhanHistoricalService.class));

        YearMonth nearerMonth = YearMonth.from(LocalDate.now().plusMonths(1));
        YearMonth requestedYearMonth = YearMonth.from(LocalDate.now().plusMonths(2));
        String nearerExpiry = nearerMonth.atEndOfMonth().format(EXPIRY_FORMAT);
        String requestedExpiry = requestedYearMonth.atEndOfMonth().format(EXPIRY_FORMAT);

        when(scriptMasterRepository.findAllOptionExpiriesByTradingSymbolAndOptionType("ULTRACEMCO", "CE"))
                .thenReturn(List.of(nearerExpiry, requestedExpiry));

        String expiry = service.nearestExpiry("ULTRACEMCO", "CE", requestedYearMonth.getMonthValue());

        assertThat(expiry).isEqualTo(requestedExpiry);
    }
}
