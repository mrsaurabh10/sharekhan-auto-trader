package org.com.sharekhan.strategy;

import org.com.sharekhan.repository.MarketHistoryCandleRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = "spring.jpa.show-sql=false")
@Import(MarketHistoryArchiveService.class)
class MarketHistoryArchiveServiceTest {
    @Autowired private MarketHistoryArchiveService archive;
    @Autowired private MarketHistoryCandleRepository repository;

    @Test
    void dailyAppendsSurviveBeyondRollingWindowAndRepeatedRunsDoNotDuplicateRows() {
        LocalDate start = LocalDate.of(2026, 9, 1);
        for (int day = 0; day < 5; day++) {
            LocalDate date = start.plusDays(day);
            List<StrategyCandle> candles = new ArrayList<>();
            for (int bar = 0; bar < 75; bar++) {
                candles.add(candle(date, LocalTime.of(9, 15).plusMinutes(5L * bar), 100));
            }
            archive.save("NIFTY", candles, "MSTOCK", date.atTime(19, 0));
            archive.save("NIFTY", candles, "MSTOCK", date.atTime(19, 5));
        }
        repository.flush();
        assertThat(repository.count()).isEqualTo(375);
        assertThat(archive.read("NIFTY", start, start.plusDays(30))).hasSize(375);
        assertThat(archive.read("NIFTY", start, start)).hasSize(75);
        assertThat(archive.read("BANKNIFTY", start, start.plusDays(30))).isEmpty();
    }

    @Test
    void rejectsMalformedAndFormingBarsAndKeepsFeedCorrectionsWithoutFallbackOverwrite() {
        LocalDate date = LocalDate.of(2026, 10, 6);
        LocalDateTime now = date.atTime(15, 26);
        StrategyCandle first = candle(date, LocalTime.of(9, 15), 100);
        archive.save("NIFTY", List.of(first, first,
                candle(date, LocalTime.of(15, 25), 100),
                candle(date, LocalTime.of(9, 16), 100),
                candle(date, LocalTime.of(9, 20, 1), 100),
                new StrategyCandle(date, LocalTime.of(9, 20), 100, 90, 99, 100, null)), "MSTOCK", now);
        archive.save("NIFTY", List.of(candle(date, LocalTime.of(9, 15), 110)), "SHAREKHAN", now);
        assertThat(archive.read("NIFTY", date, date)).singleElement()
                .extracting(row -> row.getClosePrice()).isEqualTo(100.0);
        archive.save("NIFTY", List.of(candle(date, LocalTime.of(9, 15), 101)), "MSTOCK", now);
        assertThat(archive.read("NIFTY", date, date)).singleElement()
                .extracting(row -> row.getClosePrice()).isEqualTo(101.0);
        assertThat(repository.count()).isEqualTo(1);
    }

    private StrategyCandle candle(LocalDate date, LocalTime time, double price) {
        return new StrategyCandle(date, time, price, price + 2, price - 2, price, null);
    }
}
