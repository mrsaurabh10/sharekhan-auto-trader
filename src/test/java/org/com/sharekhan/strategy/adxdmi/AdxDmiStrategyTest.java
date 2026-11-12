package org.com.sharekhan.strategy.adxdmi;

import org.com.sharekhan.dto.*;
import org.com.sharekhan.entity.*;
import org.com.sharekhan.repository.*;
import org.com.sharekhan.service.*;
import org.com.sharekhan.strategy.*;
import org.com.sharekhan.cache.QuoteCacheService;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdxDmiStrategyTest {
    @Test void inputSymbolThreeLotsIntradayAndOwnedBrokerAreRequired(){
        var brokers=mock(BrokerCredentialsRepository.class);var feeds=mock(TradeExecutionService.class);
        var strategy=new AdxDmiStrategy(brokers,mock(ScriptMasterRepository.class),mock(StrategySupport.class),mock(AdxDmiMarketData.class),feeds,mock(AdxDmiGreeks.class),mock(NseMarketCalendar.class),mock(AdxDmiContractMetadata.class));
        var r=new StrategyApplyRequest();r.setSymbol("sensex");r.setUserId(1L);r.setBrokerCredentialsId(2L);
        when(brokers.findById(2L)).thenReturn(Optional.of(BrokerCredentialsEntity.builder().appUserId(1L).brokerName("Simulator").active(true).build()));
        strategy.validateConfiguration(r);assertEquals("SENSEX",r.getSymbol());assertEquals(3,r.getLots());assertNull(r.getRiskSettings());assertTrue(r.getIntraday());
        when(brokers.findById(2L)).thenReturn(Optional.of(BrokerCredentialsEntity.builder().appUserId(1L).brokerName("Sharekhan").active(true).build()));
        assertDoesNotThrow(()->strategy.validateConfiguration(r));
        when(brokers.findById(2L)).thenReturn(Optional.of(BrokerCredentialsEntity.builder().appUserId(9L).brokerName("Simulator").active(true).build()));
        assertThrows(IllegalArgumentException.class,()->strategy.validateConfiguration(r));
        r.setLots(2);assertThrows(IllegalArgumentException.class,()->AdxDmiStrategy.validate(r));
        r.setLots(3);r.setSymbol("RELIANCE");assertDoesNotThrow(()->AdxDmiStrategy.validate(r));
        r.setSymbol("BANKNIFTY");assertDoesNotThrow(()->AdxDmiStrategy.validate(r));
        r.setSymbol(" ");assertThrows(IllegalArgumentException.class,()->AdxDmiStrategy.validate(r));verifyNoInteractions(feeds);
    }
    static ScriptMasterEntity option(int code,String side,String expiry,double strike){return ScriptMasterEntity.builder().scripCode(code).tradingSymbol("NIFTY")
            .exchange("NF").optionType(side).expiry(expiry).strikePrice(strike).lotSize(65).tickSize(.05).instrumentType("OI").build();}
    @Test void selectsExactSideExpiryAndExchangeForBothIndices(){
        var day=LocalDate.of(2026,11,9);
        var a=option(1,"PE","10/11/2026",23000);var b=option(2,"CE","03/11/2026",23000);
        var c=option(3,"CE","10/11/2026",23000);var d=option(4,"CE","10/11/2026",23050);
        var rows=List.of(a,b,c,d,option(5,"CE","17/11/2026",23025));
        assertEquals(3,AdxDmiStrategy.contract(rows,"NIFTY","NF","CE",23025,day).getScripCode());
        assertEquals(1,AdxDmiStrategy.contract(rows,"NIFTY","NF","PE",23025,day).getScripCode());
        var sensex=option(9,"CE","12/11/2026",80000);sensex.setTradingSymbol("SENSEX");sensex.setExchange("BF");sensex.setLotSize(20);
        assertEquals(9,AdxDmiStrategy.contract(List.of(c,sensex),"SENSEX","BF","CE",80010,day).getScripCode());
        assertThrows(IllegalStateException.class,()->AdxDmiStrategy.contract(List.of(c),"SENSEX","BF","CE",80010,day));
        assertThrows(IllegalStateException.class,()->AdxDmiStrategy.contract(List.of(b),"NIFTY","NF","CE",23000,day));
    }
    @Test void genericSymbolsResolveTheirListedExchangeContractsAndLotSize(){
        var stock=option(123,"CE","10/11/2026",1400);stock.setTradingSymbol("RELIANCE");stock.setLotSize(500);
        var bank=option(456,"PE","10/11/2026",56000);bank.setTradingSymbol("BANKNIFTY");bank.setLotSize(30);
        var day=LocalDate.of(2026,11,9);
        assertEquals(500,AdxDmiStrategy.contract(List.of(stock,bank),"RELIANCE","NF","CE",1401,day).getLotSize());
        assertEquals(456,AdxDmiStrategy.contract(List.of(stock,bank),"BANKNIFTY","NF","PE",56010,day).getScripCode());
        assertEquals("NF",AdxDmiRules.optionExchange("NC"));assertEquals("BF",AdxDmiRules.optionExchange("BC"));
        assertThrows(IllegalArgumentException.class,()->AdxDmiRules.optionExchange("MCX"));
    }
    @Test void absentSpotTickUsesExplicitStrategyIncrementAndValidMetadataWins(){
        assertEquals(.05,AdxDmiRules.spotTick(null,.05));
        assertEquals(.01,AdxDmiRules.spotTick(.01,.05));
        assertThrows(IllegalArgumentException.class,()->AdxDmiRules.spotTick(Double.NaN,.05));
        assertThrows(IllegalArgumentException.class,()->AdxDmiRules.spotTick(null,0));
    }
    @Test void missingIndexTickDoesNotPreventLoadingCompletedSignalCandles(){
        var brokers=mock(BrokerCredentialsRepository.class);var support=mock(StrategySupport.class);var calendar=mock(NseMarketCalendar.class);
        var strategy=new AdxDmiStrategy(brokers,mock(ScriptMasterRepository.class),support,mock(AdxDmiMarketData.class),mock(TradeExecutionService.class),mock(AdxDmiGreeks.class),calendar,mock(AdxDmiContractMetadata.class));
        var r=new StrategyApplyRequest();r.setSymbol("NIFTY");r.setUserId(3L);r.setBrokerCredentialsId(4L);
        var now=LocalDateTime.of(2026,11,10,10,0);
        when(brokers.findById(4L)).thenReturn(Optional.of(BrokerCredentialsEntity.builder().appUserId(3L).active(true).brokerName("Simulator").build()));
        when(calendar.isTradingDay(now.toLocalDate())).thenReturn(true);
        var spot=ScriptMasterEntity.builder().scripCode(20000).tradingSymbol("NIFTY").exchange("NC").build();
        when(support.resolveSpotScript("NIFTY")).thenReturn(spot);
        when(support.loadCompletedIndicatorCandles(spot,200,now)).thenReturn(new CandleLoad(List.of(),false,"No candles"));
        when(support.waiting(any(),eq("NIFTY"),anyString())).thenAnswer(call->StrategyApplyResponse.builder().message(call.getArgument(2)).build());
        assertTrue(strategy.apply(r,now).getMessage().contains("completed qualifying"));
        verify(support).loadCompletedIndicatorCandles(spot,200,now);
    }
}
