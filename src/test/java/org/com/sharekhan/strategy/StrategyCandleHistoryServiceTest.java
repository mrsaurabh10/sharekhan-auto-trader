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
    void fnoAtr75HistoryRetainsFullPreviousSessionBeforeDiscardingUnfinishedAndNonSessionRows() {
        LocalDateTime now=LocalDateTime.of(2026,11,16,9,30);
        var previous=session(now.toLocalDate().minusDays(3),75);
        history.mergeAndSave("NC:10604",previous,now.minusDays(2));
        entityManager.flush();entityManager.clear();
        var incoming=new ArrayList<StrategyCandle>(List.of(
                candle(now.toLocalDate(),LocalTime.of(9,0),101),
                candle(now.toLocalDate(),LocalTime.of(9,15),102),
                candle(now.toLocalDate(),LocalTime.of(9,20),103),
                candle(now.toLocalDate(),LocalTime.of(9,25),104),
                candle(now.toLocalDate(),LocalTime.of(9,30),105),
                candle(now.toLocalDate().minusDays(3),LocalTime.of(15,30),106)));
        var restarted=new StrategyCandleHistoryService(repository);
        var complete=restarted.mergeAndSave("NC:10604",incoming,now);
        assertThat(complete).hasSize(78);
        assertThat(complete.get(77).date().atTime(complete.get(77).time())).isEqualTo(now.minusMinutes(5));
        assertThat(complete).allMatch(c->!c.time().isBefore(LocalTime.of(9,15)) && c.time().isBefore(LocalTime.of(15,30)));
    }

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

    @Test
    void irregularSharekhanHistoryBootstrapsAtrBeforeOpeningWithoutAcceptingFormingOrOffGridBars() {
        LocalDateTime now=LocalDateTime.of(2026,11,25,9,25);
        var raw=new org.json.JSONArray();
        for (int day:new int[]{23,20}) {
            for (int i=0;i<75;i++) {
                var end=LocalTime.of(9,19,57).plusMinutes(5L*i);
                raw.put(new org.json.JSONObject().put("tradeDate",day+"/11/2026").put("tradeTime",end.toString())
                        .put("open",590).put("high",592).put("low",589).put("close",591).put("qty",1000));
            }
        }
        for (String time:List.of("09:19:52","09:24:54","09:29:51","09:34:57","15:28:03")) {
            raw.put(new org.json.JSONObject().put("tradeDate","25/11/2026").put("tradeTime",time)
                    .put("open",590).put("high",592).put("low",589).put("close",591));
        }
        var parser=new org.com.sharekhan.service.SharekhanHistoricalService(null,null,null,null);
        List<org.com.sharekhan.service.SharekhanHistoricalService.HistoricalCandle> parsed=
                org.springframework.test.util.ReflectionTestUtils.invokeMethod(parser,"parseHistoricalCandles",new org.json.JSONObject().put("data",raw));
        var normalized=parsed.stream().map(row -> {
            org.com.sharekhan.service.SharekhanHistoricalService.HistoricalCandle n=
                    org.springframework.test.util.ReflectionTestUtils.invokeMethod(parser,"normalizeFiveMinuteCandle",row);
            return new StrategyCandle(n.date(),n.time(),n.open(),n.high(),n.low(),n.close(),n.volume());
        }).toList();
        var complete=history.mergeAndSave("NC:467",normalized,now);
        assertThat(complete).hasSize(152);
        assertThat(complete).allMatch(c -> c.date().atTime(c.time()).plusMinutes(5).compareTo(now)<=0);
        assertThat(complete.get(151).date().atTime(complete.get(151).time())).isEqualTo(now.minusMinutes(5));
        entityManager.flush();entityManager.clear();
        var afterRestart=new StrategyCandleHistoryService(repository).mergeAndSave("NC:467",List.of(),now);
        assertThat(afterRestart).containsExactlyElementsOf(complete);
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
