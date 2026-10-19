package org.com.sharekhan.strategy;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.com.sharekhan.entity.*;
import org.com.sharekhan.repository.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import static org.assertj.core.api.Assertions.*;

@DataJpaTest(properties="spring.jpa.hibernate.ddl-auto=create-drop",showSql=false)
@ActiveProfiles("onboarding-test")
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class MoverSelectionSnapshotStoreTest {
    @Autowired MoverSelectionSnapshotRepository repository;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager transactions;
    final LocalDateTime opening=LocalDateTime.of(2026,10,19,9,25);
    @BeforeEach void clean() { repository.deleteAll(); }
    MoverSelectionSnapshotStore store(LocalDateTime now) {
        return new MoverSelectionSnapshotStore(repository,em,transactions,new ObjectMapper(),
                Clock.fixed(now.atZone(StrategySupport.MARKET_ZONE).toInstant(),StrategySupport.MARKET_ZONE));
    }
    List<Fno925Candidate> candidates(String symbol,String side) {
        return List.of(new Fno925Candidate(symbol,ScriptMasterEntity.builder().scripCode(123)
                .tradingSymbol(symbol).exchange("NC").instrumentType("EQ").lotSize(1).build(),side));
    }
    @ParameterizedTest @ValueSource(strings={"FNO_0925_MOVER_ATR_BREAKOUT","STOCK_0925_MOVER_ATR_BREAKOUT"})
    void restartRestoresOriginalRankOrderSideAndContractWithoutFetchingCurrentMovers(String template) {
        var second = new Fno925Candidate("SECOND",ScriptMasterEntity.builder().scripCode(456)
                .tradingSymbol("SECOND").exchange("NC").build(),"PE");
        var selected = List.of(candidates("ORIGINAL","PE").getFirst(),second);
        store(opening).loadOrCapture(template,opening,()->selected);
        var late=opening.plusHours(2);
        var restored=store(late).loadOrCapture(template,late,()->{throw new AssertionError("Must not rescan");}).orElseThrow();
        assertThat(restored).isEqualTo(selected);
        assertThat(repository.count()).isOne();
        var saved=repository.findAll().getFirst();
        assertThat(saved.getCaptureStartedAt()).isEqualTo(opening);
        assertThat(saved.getCapturedAt()).isEqualTo(opening);
    }
    @ParameterizedTest @ValueSource(strings={"FNO_0925_MOVER_ATR_BREAKOUT","STOCK_0925_MOVER_ATR_BREAKOUT"})
    void lateStartWithoutOpeningSnapshotDoesNotScanOrSave(String template) {
        for(var now:List.of(opening.minusMinutes(1),opening.plusMinutes(1),opening.plusHours(2))) {
            assertThat(store(now).loadOrCapture(template,now,()->{throw new AssertionError("Late scan");})).isEmpty();
        }
        assertThat(repository.count()).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"FNO_0925_MOVER_ATR_BREAKOUT","STOCK_0925_MOVER_ATR_BREAKOUT"})
    void emptyOpeningSelectionStaysEmptyAfterRestart(String template) {
        store(opening).loadOrCapture(template,opening,List::of);
        var later=opening.plusHours(1);
        assertThat(store(later).loadOrCapture(template,later,()->candidates("LATE","CE"))).contains(List.of());
    }
    @ParameterizedTest @ValueSource(strings={"FNO_0925_MOVER_ATR_BREAKOUT","STOCK_0925_MOVER_ATR_BREAKOUT"})
    void failedFeedCaptureCanRetryWithinWindowButCannotFreezeLaterMovers(String template) {
        assertThatThrownBy(()->store(opening).loadOrCapture(template,opening,()->{throw new IllegalStateException("feed unavailable");}))
                .isInstanceOf(IllegalStateException.class);
        assertThat(repository.count()).isZero();
        assertThat(store(opening.plusSeconds(20)).loadOrCapture(template,opening.plusSeconds(20),()->candidates("ABC","CE")))
                .isPresent();
    }
    @ParameterizedTest @ValueSource(strings={"FNO_0925_MOVER_ATR_BREAKOUT","STOCK_0925_MOVER_ATR_BREAKOUT"})
    void slowCaptureThatCompletesAfterOpeningWindowIsNotSaved(String template) {
        assertThat(store(opening.plusMinutes(1)).loadOrCapture(template,opening,()->candidates("LATE","CE"))).isEmpty();
        assertThat(repository.count()).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"FNO_0925_MOVER_ATR_BREAKOUT","STOCK_0925_MOVER_ATR_BREAKOUT"})
    void nextDayGetsItsOwnSelectionWithoutReusingYesterday(String template) {
        store(opening).loadOrCapture(template,opening,()->candidates("FIRST","CE"));
        var next=opening.plusDays(1);
        var selected=store(next).loadOrCapture(template,next,()->candidates("SECOND","PE")).orElseThrow();
        assertThat(selected.getFirst().symbol()).isEqualTo("SECOND");
        assertThat(repository.count()).isEqualTo(2);
        assertThat(store(next.plusHours(2)).loadOrCapture(template,next.plusHours(2),()->List.of())).contains(selected);
    }
    @ParameterizedTest @ValueSource(strings={"FNO_0925_MOVER_ATR_BREAKOUT","STOCK_0925_MOVER_ATR_BREAKOUT"})
    void concurrentApplicationInstancesUseTheSameFirstCommittedSnapshot(String template) throws Exception {
        var barrier=new CyclicBarrier(2);
        try(var executor=Executors.newFixedThreadPool(2)) {
            List<Future<List<Fno925Candidate>>> results=new ArrayList<>();
            for(String symbol:List.of("ONE","TWO")) {
                results.add(executor.submit(()->store(opening).loadOrCapture(template,opening,()->{
                    try {barrier.await(5,TimeUnit.SECONDS);}catch(Exception e){throw new IllegalStateException(e);}
                    return candidates(symbol,"CE");
                }).orElseThrow()));
            }
            assertThat(results.get(0).get(10,TimeUnit.SECONDS)).isEqualTo(results.get(1).get(10,TimeUnit.SECONDS));
            assertThat(repository.count()).isOne();
        }
    }
    @ParameterizedTest @ValueSource(strings={"FNO_0925_MOVER_ATR_BREAKOUT","STOCK_0925_MOVER_ATR_BREAKOUT"})
    void invalidContractIdentityIsNotPersisted(String template) {
        var bad=List.of(new Fno925Candidate("ABC",ScriptMasterEntity.builder().build(),"CE"));
        assertThatThrownBy(()->store(opening).loadOrCapture(template,opening,()->bad)).isInstanceOf(IllegalArgumentException.class);
        assertThat(repository.count()).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"FNO_0925_MOVER_ATR_BREAKOUT","STOCK_0925_MOVER_ATR_BREAKOUT"})
    void corruptStoredSnapshotStopsWithoutRescanning(String template) {
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
        tx.executeWithoutResult(s->em.persist(MoverSelectionSnapshot.builder().id(template+"@"+opening.toLocalDate())
            .templateId(template).tradingDay(opening.toLocalDate()).captureStartedAt(opening).capturedAt(opening)
            .schemaVersion(1).candidatesJson("invalid-json").build()));
        assertThatThrownBy(()->store(opening.plusHours(1)).loadOrCapture(template,opening.plusHours(1),()->{throw new AssertionError("rescan");}))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("refusing to rescan");
    }
    @Test void cashAndFnoSelectionsOnTheSameDayRemainIndependentAfterRestart() {
        String fno = Fno0925MoverAtrBreakoutStrategy.TEMPLATE_ID;
        String cash = Stock0925MoverAtrBreakoutStrategy.TEMPLATE_ID;
        var optionList = candidates("OPTION", "PE");
        var cashList = candidates("CASH", "CE");
        store(opening).loadOrCapture(fno, opening, () -> optionList);
        store(opening).loadOrCapture(cash, opening, () -> cashList);
        var later = opening.plusHours(2);
        assertThat(store(later).loadOrCapture(fno, later, () -> {throw new AssertionError("rescan");})).contains(optionList);
        assertThat(store(later).loadOrCapture(cash, later, () -> {throw new AssertionError("rescan");})).contains(cashList);
        assertThat(repository.count()).isEqualTo(2);
    }

}
