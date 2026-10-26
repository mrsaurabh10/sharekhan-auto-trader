package org.com.sharekhan.service;

import org.com.sharekhan.cache.*;
import org.com.sharekhan.entity.*;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.com.sharekhan.repository.TriggerTradeRequestRepository;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FnoMoverExitPolicyServiceTest {
    final ZoneId zone=ZoneId.of("Asia/Kolkata");
    final Instant start=LocalDateTime.of(2026,10,26,10,0).atZone(zone).toInstant();
    JdbcTemplate jdbc;QuoteCacheService quotes;LtpCacheService prices;TriggerTradeRequestRepository requests;
    TransactionTemplate tx;FnoMoverExitPolicyService service;TriggeredTradeSetupEntity trade;
    @BeforeEach void setup() throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1","sa","");
        jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        String schema=new String(new ClassPathResource("db/postgresql/fno-mover-exit-state.sql").getInputStream().readAllBytes(),StandardCharsets.UTF_8);
        for(String sql:schema.split(";")) if(!sql.isBlank())jdbc.execute(sql);
        quotes=mock(QuoteCacheService.class);prices=mock(LtpCacheService.class);requests=mock(TriggerTradeRequestRepository.class);
        service=instance();
        trade=TriggeredTradeSetupEntity.builder().id(1L).appUserId(4L).brokerCredentialsId(6L).triggerRequestId(2L)
                .source(FnoMoverExitPolicyService.SOURCE).symbol("TEST").exchange("NF").scripCode(123).spotScripCode(456)
                .intraday(true).optionType("PE").entryAction("BUY").entryPrice(100d).stopLoss(102d)
                .actualEntryPrice(10d).entryAt(LocalDateTime.ofInstant(start,zone)).quantity(100L).lots(1)
                .useSpotForSl(true).status(TriggeredTradeStatus.EXECUTED).build();
    }
    FnoMoverExitPolicyService instance() {
        var s=new FnoMoverExitPolicyService(jdbc,quotes,prices,requests);ReflectionTestUtils.setField(s,"enabled",true);return s;
    }
    QuoteCacheService.QuoteSnapshot book(Instant at,double bid,String source,Double qty) {
        return QuoteCacheService.QuoteSnapshot.builder().scripCode(123).bookSource(source).bookExchange("NF")
                .bookProviderIdentity("123:TEST26NOV26P100").coherentBook(true).bestBid(bid).bestAsk(bid+.1)
                .lastBookAt(at).bidQuantity(qty).build();
    }
    FnoMoverExitPolicyService.Decision observe(Instant at,double spot,double bid) {
        when(prices.getLtp(456)).thenReturn(spot);when(prices.getObservedAt(456)).thenReturn(LocalDateTime.ofInstant(at,zone));
        when(quotes.getSnapshot(123)).thenReturn(Optional.of(book(at,bid,"SHOONYA",100d)));
        return tx.execute(status->service.evaluate(trade,at));
    }
    @Test void neverProgressingTradeExitsAt45MinutesFromFillNotBefore() {
        for(int seconds=0;seconds<2700;seconds+=10) assertThat(observe(start.plusSeconds(seconds),100,10).exit()).isFalse();
        assertThat(observe(start.plusSeconds(2700),100,9.9).reason()).isEqualTo(FnoMoverExitPolicyService.NO_PROGRESS);
    }
    @Test void reachedHalfRiskPersistsAcrossRestartAndNeverBecomesNoProgressOnReversal() {
        observe(start,100,10);observe(start.plusSeconds(10),99,10);
        service=instance();
        for(int seconds=20;seconds<=2800;seconds+=10) assertThat(observe(start.plusSeconds(seconds),100,10).exit()).isFalse();
        assertThat(jdbc.queryForObject("SELECT reached_half_risk FROM fno_mover_exit_state WHERE trade_id=1",Boolean.class)).isTrue();
    }
    @Test void freshStockProgressCountsEvenWhenOptionBookIsUnknown() {
        observe(start,100,10);
        var at=start.plusSeconds(10);when(prices.getLtp(456)).thenReturn(99d);when(prices.getObservedAt(456)).thenReturn(LocalDateTime.ofInstant(at,zone));
        when(quotes.getSnapshot(123)).thenReturn(Optional.of(book(at,10,"UNKNOWN",100d)));
        tx.execute(s->service.evaluate(trade,at));
        assertThat(jdbc.queryForObject("SELECT reached_half_risk FROM fno_mover_exit_state WHERE trade_id=1",Boolean.class)).isTrue();
    }
    @Test void premiumPeakAndFloorSurviveRestartAndCloseAllRemainingLots() {
        observe(start,100,10);observe(start.plusSeconds(5),99,11);observe(start.plusSeconds(10),99,12);
        service=instance();
        var decision=observe(start.plusSeconds(15),100,10.9);
        assertThat(decision.reason()).isEqualTo(FnoMoverExitPolicyService.PROFIT_PROTECTION);
        assertThat(decision.bid()).isEqualTo(10.9);
        assertThat(jdbc.queryForObject("SELECT peak_bid FROM fno_mover_exit_state WHERE trade_id=1",Double.class)).isEqualTo(12);
        assertThat(jdbc.queryForObject("SELECT protection_floor FROM fno_mover_exit_state WHERE trade_id=1",Double.class)).isEqualTo(11);
    }
    @Test void feeUnprofitableTinyPremiumGainCannotArmProfitProtection() {
        trade.setActualEntryPrice(.05);
        observe(start,100,.05);
        assertThat(observe(start.plusSeconds(5),99,.06).exit()).isFalse();
        assertThat(jdbc.queryForObject("SELECT protection_armed FROM fno_mover_exit_state WHERE trade_id=1",Boolean.class)).isFalse();
    }

    @Test void remainingPartialPositionInheritsRatchetInsteadOfResettingItsPeak() {
        observe(start,100,10);observe(start.plusSeconds(5),99,12);
        var remaining=TriggeredTradeSetupEntity.builder().id(3L).appUserId(4L).brokerCredentialsId(6L)
                .source(FnoMoverExitPolicyService.SOURCE).symbol("TEST").exchange("NF").scripCode(123).spotScripCode(456)
                .intraday(true).optionType("PE").entryAction("BUY").entryPrice(100d).stopLoss(10d).useSpotForSl(false)
                .actualEntryPrice(10d).entryAt(trade.getEntryAt()).quantity(100L).lots(1).build();
        tx.executeWithoutResult(s->service.inherit(trade,remaining));
        trade=remaining;
        assertThat(observe(start.plusSeconds(10),100,10.9).reason()).isEqualTo(FnoMoverExitPolicyService.PROFIT_PROTECTION);
        assertThat(jdbc.queryForObject("SELECT peak_bid FROM fno_mover_exit_state WHERE trade_id=3",Double.class)).isEqualTo(12);
    }

    @Test void staleUnknownWrongContractCrossedAndInsufficientDepthCannotArmOrExit() {
        observe(start,100,10);var at=start.plusSeconds(5);
        var good=book(at,12,"SHOONYA",100d);
        for(var b:List.of(book(start.minusSeconds(1),12,"SHOONYA",100d),book(at,12,"UNKNOWN",100d),book(at,12,"SHOONYA",99d),
                QuoteCacheService.QuoteSnapshot.builder().bestBid(12d).bestAsk(11d).coherentBook(true).lastBookAt(at).build(),
                QuoteCacheService.QuoteSnapshot.builder().bestBid(12d).bestAsk(12.1).coherentBook(true).lastBookAt(at).bookSource("SHOONYA").bookExchange("NF").bookProviderIdentity("999:OTHER").bidQuantity(100d).build())) {
            when(quotes.getSnapshot(123)).thenReturn(Optional.of(b));
            assertThat(tx.execute(s->service.evaluate(trade,at)).exit()).isFalse();
        }
        assertThat(jdbc.queryForObject("SELECT protection_armed FROM fno_mover_exit_state WHERE trade_id=1",Boolean.class)).isFalse();
    }
    @Test void lateAdoptionOrObservationGapCannotAssertNeverProgressed() {
        assertThat(observe(start.plusSeconds(2700),100,10).exit()).isFalse();
        assertThat(jdbc.queryForObject("SELECT progress_coverage_valid FROM fno_mover_exit_state WHERE trade_id=1",Boolean.class)).isFalse();
        jdbc.update("DELETE FROM fno_mover_exit_state");observe(start,100,10);
        observe(start.plusSeconds(20),100,10);
        for(int seconds=30;seconds<=2700;seconds+=10) assertThat(observe(start.plusSeconds(seconds),100,10).exit()).isFalse();
    }
    @Test void originalRiskComesFromOwnedRequestNotModifiedStop() {
        trade.setStopLoss(10d);trade.setUseSpotForSl(false);
        when(requests.findById(2L)).thenReturn(Optional.of(TriggerTradeRequestEntity.builder().id(2L).appUserId(4L).brokerCredentialsId(6L)
                .source(FnoMoverExitPolicyService.SOURCE).scripCode(123).entryPrice(100d).stopLoss(102d).build()));
        observe(start,100,10);observe(start.plusSeconds(10),99,10);
        assertThat(jdbc.queryForObject("SELECT initial_spot_stop FROM fno_mover_exit_state WHERE trade_id=1",Double.class)).isEqualTo(102);
        assertThat(jdbc.queryForObject("SELECT reached_half_risk FROM fno_mover_exit_state WHERE trade_id=1",Boolean.class)).isTrue();
    }
    @Test void callsMeasureUpwardProgressAndFlatBoundaryDoesNotTriggerTimerExit() {
        trade.setOptionType("CE");trade.setStopLoss(98d);observe(start,100,10);observe(start.plusSeconds(5),101,10);
        assertThat(jdbc.queryForObject("SELECT reached_half_risk FROM fno_mover_exit_state WHERE trade_id=1",Boolean.class)).isTrue();
    }
    @Test void featureDisabledOtherStrategiesCashAndNonIntradayNeverCreateState() {
        for(String source:List.of("manual","strategy:STOCK_0925_MOVER_ATR_BREAKOUT","ngas")) {trade.setSource(source);observe(start,100,12);}
        trade.setSource(FnoMoverExitPolicyService.SOURCE);trade.setIntraday(false);observe(start,100,12);
        trade.setIntraday(true);trade.setEntryAction("SELL");observe(start,100,12);
        trade.setEntryAction("BUY");ReflectionTestUtils.setField(service,"enabled",false);observe(start,100,12);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fno_mover_exit_state",Integer.class)).isZero();
    }
    @Test void noDecisionOutsideMarketOrOnDifferentSessionAndFutureSpotCannotProveProgress() {
        observe(start,100,10);
        assertThat(observe(start.plusSeconds(6*3600),100,12).exit()).isFalse();
        assertThat(observe(start.plusSeconds(24*3600),100,12).exit()).isFalse();
        when(prices.getLtp(456)).thenReturn(98d);when(prices.getObservedAt(456)).thenReturn(LocalDateTime.ofInstant(start.plusSeconds(10),zone));
        when(quotes.getSnapshot(123)).thenReturn(Optional.of(book(start.plusSeconds(5),10,"SHOONYA",100d)));
        tx.execute(s->service.evaluate(trade,start.plusSeconds(5)));
        assertThat(jdbc.queryForObject("SELECT reached_half_risk FROM fno_mover_exit_state WHERE trade_id=1",Boolean.class)).isFalse();
    }
}
