package org.com.sharekhan.service;

import org.com.sharekhan.cache.QuoteCacheService;
import org.com.sharekhan.entity.*;
import org.com.sharekhan.repository.BrokerCredentialsRepository;
import org.com.sharekhan.strategy.mcx.NaturalGasScalpingStrategy;
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

class NaturalGasScalpProfitLockServiceTest {
    final ZoneId zone=ZoneId.of("Asia/Kolkata");
    final Instant start=LocalDateTime.of(2026,11,5,13,0).atZone(zone).toInstant();
    JdbcTemplate jdbc; QuoteCacheService quotes; BrokerCredentialsRepository brokers;
    NaturalGasScalpProfitLockService service; TriggeredTradeSetupEntity trade; TransactionTemplate tx;
    @BeforeEach void setup() throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1","sa","");
        jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        String schema=new String(new ClassPathResource("db/postgresql/ngas-scalp-profit-lock.sql").getInputStream().readAllBytes(),StandardCharsets.UTF_8);
        for(String sql:schema.split(";")) if(!sql.isBlank())jdbc.execute(sql);
        quotes=mock(QuoteCacheService.class);brokers=mock(BrokerCredentialsRepository.class);
        when(brokers.findById(4L)).thenReturn(Optional.of(BrokerCredentialsEntity.builder().id(4L).appUserId(3L).brokerName("Simulator").active(true).build()));
        service=instance();
        trade=TriggeredTradeSetupEntity.builder().id(1L).appUserId(3L).brokerCredentialsId(4L)
                .source(NaturalGasScalpingStrategy.SOURCE).symbol("NATURALGAS").exchange("MX").scripCode(583842)
                .intraday(true).optionType("CE").entryAction("BUY").actualEntryPrice(16.4)
                .entryAt(LocalDateTime.ofInstant(start,zone)).quantity(1250L).lots(1).build();
    }
    NaturalGasScalpProfitLockService instance() {
        var s=new NaturalGasScalpProfitLockService(jdbc,quotes,brokers);ReflectionTestUtils.setField(s,"enabled",true);return s;
    }
    QuoteCacheService.QuoteSnapshot.QuoteSnapshotBuilder builder(Instant at,double bid) {
        return QuoteCacheService.QuoteSnapshot.builder().scripCode(583842).bookSource("SHAREKHAN_WS").bookExchange("MX")
                .bookProviderIdentity("scrip:583842").coherentBook(true).bestBid(bid).bestAsk(bid+.05).lastBookAt(at);
    }
    QuoteCacheService.QuoteSnapshot book(Instant at,double bid) { return builder(at,bid).build(); }
    NaturalGasScalpProfitLockService.Decision observe(Instant at,double bid) {
        when(quotes.getSnapshot(583842)).thenReturn(Optional.of(book(at,bid)));
        return tx.execute(s->service.evaluate(trade,at));
    }
    double value(String column) { return jdbc.queryForObject("SELECT "+column+" FROM ngas_scalp_profit_lock_state WHERE trade_id=1",Double.class); }
    @Test void fivePercentArmsAndRetainsHalfThePeakGainAtTheLowerTick() {
        assertThat(observe(start,17.20).exit()).isFalse();assertThat(value("protection_floor")).isZero();
        assertThat(observe(start.plusSeconds(1),17.25).exit()).isFalse();assertThat(value("protection_floor")).isEqualTo(16.8);
        assertThat(observe(start.plusSeconds(2),17.45).exit()).isFalse();assertThat(value("protection_floor")).isEqualTo(16.9);
        assertThat(observe(start.plusSeconds(3),17.0).exit()).isFalse();assertThat(value("protection_floor")).isEqualTo(16.9);
        assertThat(observe(start.plusSeconds(4),16.9).bid()).isEqualTo(16.9);
        assertThat(jdbc.queryForList("SELECT event_type FROM ngas_scalp_profit_lock_event",String.class)).containsExactly("ARMED","FLOOR_RAISED","EXIT_REQUESTED");
    }
    @Test void peakAndFloorSurviveRestartAndGapExitUsesActualBid() {
        observe(start,17.5);service=instance();
        assertThat(observe(start.plusSeconds(60),16.5).bid()).isEqualTo(16.5);
        assertThat(value("peak_bid")).isEqualTo(17.5);assertThat(value("protection_floor")).isEqualTo(16.95);
    }
    @Test void loserNeverArmsAndNoTimeoutExitIsAdded() {
        assertThat(observe(start,16.45).exit()).isFalse();
        assertThat(observe(start.plusSeconds(3600),14.9).exit()).isFalse();
        assertThat(value("protection_floor")).isZero();
    }
    @Test void rejectsUnknownStaleWrongIdentityWrongExchangeAndOneSidedBooks() {
        observe(start,17.5);
        for(var invalid:List.of(
                builder(start,16.5).bookSource("UNKNOWN").build(),
                builder(start,16.5).bookProviderIdentity("scrip:583843").build(),
                builder(start,16.5).bookExchange("NF").build(),
                builder(start,16.5).coherentBook(false).build(),
                builder(start,16.5).lastBookAt(start.minusSeconds(1)).build(),
                builder(start,16.5).lastBookAt(start.plusSeconds(2)).build(),
                builder(start,16.5).bestAsk(16d).build())) {
            when(quotes.getSnapshot(583842)).thenReturn(Optional.of(invalid));
            assertThat(tx.execute(s->service.evaluate(trade,start.plusSeconds(1))).exit()).isFalse();
        }
        when(quotes.getSnapshot(583842)).thenReturn(Optional.of(book(start,16.5)));
        assertThat(tx.execute(s->service.evaluate(trade,start.plusSeconds(6))).exit()).isFalse();
        assertThat(service.currentExitBid(trade,start.plusSeconds(6))).isNull();
        assertThat(value("peak_bid")).isEqualTo(17.5);
    }
    @Test void simulatorOwnerSizeSourceAndSessionAreRequired() {
        for(String broker:List.of("SHOONYA","SHAREKHAN")) {
            when(brokers.findById(4L)).thenReturn(Optional.of(BrokerCredentialsEntity.builder().id(4L).appUserId(3L).brokerName(broker).active(true).build()));
            assertThat(observe(start,18).exit()).isFalse();
        }
        when(brokers.findById(4L)).thenReturn(Optional.of(BrokerCredentialsEntity.builder().id(4L).appUserId(99L).brokerName("SIMULATOR").active(true).build()));
        observe(start,18);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ngas_scalp_profit_lock_state",Integer.class)).isZero();
    }
    @Test void disabledWrongSizeOtherStrategyAndNextDayCannotCreateState() {
        ReflectionTestUtils.setField(service,"enabled",false);observe(start,18);
        ReflectionTestUtils.setField(service,"enabled",true);trade.setLots(2);observe(start,18);
        trade.setLots(1);trade.setSource("strategy:MCX_NATURALGAS_ATM_BREAKOUT");observe(start,18);
        trade.setSource(NaturalGasScalpingStrategy.SOURCE);observe(start.plusSeconds(86400),18);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ngas_scalp_profit_lock_state",Integer.class)).isZero();
    }
    @Test void entryMutationCannotReuseOldProtection() {
        observe(start,17.5);trade.setActualEntryPrice(16.5);
        assertThat(observe(start.plusSeconds(1),16.5).exit()).isFalse();
        assertThat(value("initial_premium")).isEqualTo(16.4);
    }
}
