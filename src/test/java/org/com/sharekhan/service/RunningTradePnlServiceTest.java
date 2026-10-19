package org.com.sharekhan.service;

import java.time.*;
import java.util.*;
import org.com.sharekhan.auth.AccessTokenPersistenceService;
import org.com.sharekhan.cache.LtpCacheService;
import org.com.sharekhan.dhan.DhanOrderClient;
import org.com.sharekhan.entity.*;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.com.sharekhan.repository.*;
import org.com.sharekhan.util.CryptoService;
import org.json.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class RunningTradePnlServiceTest {
    LtpCacheService prices=new LtpCacheService();
    BrokerCredentialsRepository brokers=mock(BrokerCredentialsRepository.class);
    TriggeredTradeSetupRepository trades=mock(TriggeredTradeSetupRepository.class);
    AccessTokenPersistenceService tokens=mock(AccessTokenPersistenceService.class);
    CryptoService crypto=mock(CryptoService.class);
    DhanOrderClient http=mock(DhanOrderClient.class);
    RunningTradePnlService service=new RunningTradePnlService(prices,brokers,trades,tokens,crypto,http);
    Instant now=Instant.parse("2026-10-19T14:00:00Z");
    TriggeredTradeSetupEntity trade=TriggeredTradeSetupEntity.builder().id(10328L).appUserId(4L).brokerCredentialsId(6L)
        .symbol("NIFTY").scripCode(51346).exchange("NF").strikePrice(22850d).optionType("CE").expiry("27/10/2026")
        .status(TriggeredTradeStatus.EXECUTED).quantity(65L).actualEntryPrice(195.1).intraday(false).build();
    @BeforeEach void setup() {
        time(now);
        when(brokers.findById(6L)).thenReturn(Optional.of(BrokerCredentialsEntity.builder().id(6L).appUserId(4L).brokerName("Dhan").clientCode("enc").build()));
        when(crypto.decrypt("enc")).thenReturn("1000000001");
        when(tokens.findLatestByBrokerAndBrokerCredentialsId("Dhan",6L)).thenReturn(Optional.of(AccessTokenEntity.builder().token("test-token").expiry(now.plusSeconds(3600)).build()));
        when(trades.countOpenContract(anyLong(),anyString(),anyString(),anyString(),anyString(),anyDouble(),anySet())).thenReturn(1L);
    }
    void time(Instant at){ReflectionTestUtils.setField(service,"clock",Clock.fixed(at,ZoneOffset.UTC));}
    JSONObject position() {
        return new JSONObject().put("dhanClientId","1000000001").put("exchangeSegment","NSE_FNO")
            .put("tradingSymbol","NIFTY-Oct2026-22850-CE").put("drvExpiryDate","2026-10-27 14:30:00")
            .put("drvStrikePrice",22850).put("drvOptionType","CALL").put("productType","MARGIN")
            .put("netQty",65).put("buyQty",65).put("sellQty",0).put("buyAvg",195.1)
            .put("positionType","LONG").put("multiplier",1).put("unrealizedProfit",-6.5);
    }
    void broker(JSONObject row) throws Exception {
        when(http.exchange("GET","/positions","test-token",null)).thenReturn(new DhanOrderClient.Response(200,new JSONArray().put(row).toString()));
    }
    @ParameterizedTest @ValueSource(doubles={190,195.1,200})
    void lastKnownPriceRemainsUsableAfterCloseAndDisconnect(double price) {
        Instant close=Instant.parse("2026-10-19T10:00:00Z");
        ReflectionTestUtils.invokeMethod(prices,"updateLtpAt",51346,price,LocalDateTime.ofInstant(close,ZoneId.of("Asia/Kolkata")));
        trade.setBrokerCredentialsId(null);
        var result=service.snapshot(trade).orElseThrow();
        assertThat(result.price()).isEqualByComparingTo(Double.toString(price));
        assertThat(result.runningPnl()).isEqualByComparingTo(java.math.BigDecimal.valueOf(price).subtract(java.math.BigDecimal.valueOf(195.1)).multiply(java.math.BigDecimal.valueOf(65)));
        assertThat(result.observedAt()).isEqualTo(close);
        prices.clearAll();time(now.plusSeconds(600));
        assertThat(service.snapshot(trade)).contains(result);
        assertThat(trade.getPnl()).isNull();verifyNoInteractions(http);
    }
    @Test void dhanFallbackShowsBrokerLossWithoutWritingBookedPnlOrRequestingOrders() throws Exception {
        broker(position());
        var result=service.snapshot(trade).orElseThrow();
        assertThat(result.price()).isEqualByComparingTo("195");
        assertThat(result.runningPnl()).isEqualByComparingTo("-6.50");
        assertThat(result.source()).isEqualTo("Dhan position snapshot");
        assertThat(trade.getPnl()).isNull();assertThat(trade.getStatus()).isEqualTo(TriggeredTradeStatus.EXECUTED);
        service.snapshot(trade);verify(http,times(1)).exchange("GET","/positions","test-token",null);
        verify(trades,never()).save(any());
    }
    @Test void failedBrokerRefreshKeepsPreviousMarkAndTimestamp() throws Exception {
        broker(position());var previous=service.snapshot(trade).orElseThrow();time(now.plusSeconds(20));
        when(http.exchange("GET","/positions","test-token",null)).thenReturn(new DhanOrderClient.Response(400,"invalid token"));
        assertThat(service.snapshot(trade)).contains(previous);
    }
    @Test void newerPriceReplacesSavedMarkAndClosedOrChangedTradeCannotReuseIt() throws Exception {
        broker(position());service.snapshot(trade);time(now.plusSeconds(2));
        ReflectionTestUtils.invokeMethod(prices,"updateLtpAt",51346,200d,LocalDateTime.ofInstant(now.plusSeconds(1),ZoneId.of("Asia/Kolkata")));
        assertThat(service.snapshot(trade).orElseThrow().runningPnl()).isEqualByComparingTo("318.50");
        trade.setStatus(TriggeredTradeStatus.EXITED_SUCCESS);assertThat(service.snapshot(trade)).isEmpty();
        trade.setStatus(TriggeredTradeStatus.EXECUTED);trade.setActualEntryPrice(0d);assertThat(service.snapshot(trade)).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings={"dhanClientId","exchangeSegment","tradingSymbol","drvExpiryDate","drvStrikePrice","drvOptionType","productType","netQty","buyQty","buyAvg","positionType","sellQty","multiplier"})
    void mismatchedBrokerPositionNeverProducesAProfit(String field) throws Exception {
        JSONObject row=position();
        row.put(field,switch(field){case "drvStrikePrice"->22900;case "netQty","buyQty"->66;case "buyAvg"->196;case "sellQty","multiplier"->2;default->"foreign";});
        broker(row);assertThat(service.snapshot(trade)).isEmpty();
    }
    @Test void duplicateAppPositionsAndWrongAccountNeverUseAccountTotals() throws Exception {
        broker(position());when(trades.countOpenContract(anyLong(),anyString(),anyString(),anyString(),anyString(),anyDouble(),anySet())).thenReturn(2L);
        assertThat(service.snapshot(trade)).isEmpty();verifyNoInteractions(http);
        trade.setAppUserId(9L);assertThat(service.snapshot(trade)).isEmpty();verifyNoInteractions(http);
    }
    @Test void missingMarksNeverFabricateZeroAndPendingFillIsNotUnrealizedProfit() {
        trade.setStatus(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION);assertThat(service.snapshot(trade)).isEmpty();
        trade.setStatus(TriggeredTradeStatus.EXECUTED);trade.setBrokerCredentialsId(null);assertThat(service.snapshot(trade)).isEmpty();
    }
}
