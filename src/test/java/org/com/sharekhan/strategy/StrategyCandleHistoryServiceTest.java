package org.com.sharekhan.strategy;

import jakarta.persistence.EntityManager;
import org.com.sharekhan.repository.StrategyCandleHistoryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = "spring.jpa.show-sql=false")
@Import(StrategyCandleHistoryService.class)
class StrategyCandleHistoryServiceTest {
    @Autowired private StrategyCandleHistoryService history;
    @Autowired private StrategyCandleHistoryRepository repository;
    @Autowired private EntityManager entityManager;

    @Test
    void previousSessionSurvivesServiceRestartAndFormingCandleDoesNotConsumeMinimumHistory() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 5, 9, 30);
        List<StrategyCandle> previous = session(now.toLocalDate().minusDays(3), 47);
        history.mergeAndSave("NC:20000", previous, now.minusDays(2));
        entityManager.flush();
        entityManager.clear();

        StrategyCandleHistoryService restarted = new StrategyCandleHistoryService(repository);
        List<StrategyCandle> result = restarted.mergeAndSave("NC:20000", List.of(
                candle(now.toLocalDate(), LocalTime.of(9, 15), 101),
                candle(now.toLocalDate(), LocalTime.of(9, 20), 102),
                candle(now.toLocalDate(), LocalTime.of(9, 25), 103),
                candle(now.toLocalDate(), LocalTime.of(9, 30), 104)), now);

        assertThat(result).hasSize(50);
        assertThat(result.get(49).time()).isEqualTo(LocalTime.of(9, 25));
        assertThat(repository.findTop250ByInstrumentKeyOrderByCandleTimeDesc("NC:20000")).hasSize(50);
        assertThat(restarted.mergeAndSave("NC:26000", List.of(), now)).isEmpty();
    }

    @Test
    void repeatedPollsUpdateAnExistingTimestampWithoutDuplicatingIt() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 5, 10, 0);
        StrategyCandle first = candle(now.toLocalDate(), LocalTime.of(9, 25), 101);
        StrategyCandle corrected = candle(now.toLocalDate(), LocalTime.of(9, 25), 102);
        history.mergeAndSave("NC:20000", List.of(first), now);
        history.mergeAndSave("NC:20000", List.of(corrected), now);
        entityManager.flush();
        entityManager.clear();
        assertThat(history.mergeAndSave("NC:20000", List.of(), now)).containsExactly(corrected);
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void retainsOnlyTheLatest250CompletedCandlesInTheDatabase() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 5, 15, 31);
        List<StrategyCandle> incoming = new ArrayList<>();
        for (int day = 4; day > 0; day--) incoming.addAll(session(now.toLocalDate().minusDays(day), 75));
        assertThat(history.mergeAndSave("NC:20000", incoming, now)).hasSize(250);
        entityManager.flush();
        assertThat(repository.count()).isEqualTo(250);

        history.mergeAndSave("NC:20000", session(now.toLocalDate(), 75), now);
        entityManager.flush();
        entityManager.clear();
        assertThat(repository.count()).isEqualTo(250);
        assertThat(history.mergeAndSave("NC:20000", List.of(), now).get(249).time()).isEqualTo(LocalTime.of(15, 25));
    }

    private List<StrategyCandle> session(LocalDate date, int count) {
        List<StrategyCandle> result = new ArrayList<>();
        for (int i = 0; i < count; i++) result.add(candle(date, LocalTime.of(9, 15).plusMinutes(i * 5L), 100 + i));
        return result;
    }

    private StrategyCandle candle(LocalDate date, LocalTime time, double close) {
        return new StrategyCandle(date, time, close - 1, close + 1, close - 2, close, 1000L);
    }
}
