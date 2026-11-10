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
    @Test void inputSymbolThreeLotsIntradayAndOwnedSimulatorAreRequired(){
        var brokers=mock(BrokerCredentialsRepository.class);var feeds=mock(TradeExecutionService.class);
        var strategy=new AdxDmiStrategy(brokers,mock(ScriptMasterRepository.class),mock(StrategySupport.class),mock(QuoteCacheService.class),feeds,mock(AdxDmiPaperBroker.class),mock(NseMarketCalendar.class));
        var r=new StrategyApplyRequest();r.setSymbol("sensex");r.setUserId(1L);r.setBrokerCredentialsId(2L);
        when(brokers.findById(2L)).thenReturn(Optional.of(BrokerCredentialsEntity.builder().appUserId(1L).brokerName("Simulator").active(true).build()));
        strategy.validateConfiguration(r);assertEquals("SENSEX",r.getSymbol());assertEquals(3,r.getLots());assertNull(r.getRiskSettings());assertTrue(r.getIntraday());
        when(brokers.findById(2L)).thenReturn(Optional.of(BrokerCredentialsEntity.builder().appUserId(1L).brokerName("Sharekhan").active(true).build()));
        assertThrows(IllegalArgumentException.class,()->strategy.validateConfiguration(r));
        when(brokers.findById(2L)).thenReturn(Optional.of(BrokerCredentialsEntity.builder().appUserId(9L).brokerName("Simulator").active(true).build()));
        assertThrows(IllegalArgumentException.class,()->strategy.validateConfiguration(r));
        r.setLots(2);assertThrows(IllegalArgumentException.class,()->AdxDmiStrategy.validate(r));
        r.setLots(3);r.setSymbol("RELIANCE");assertThrows(IllegalArgumentException.class,()->AdxDmiStrategy.validate(r));verifyNoInteractions(feeds);
    }
    static ScriptMasterEntity option(int code,String side,String expiry,double strike){return ScriptMasterEntity.builder().scripCode(code).tradingSymbol("NIFTY")
            .exchange("NF").optionType(side).expiry(expiry).strikePrice(strike).lotSize(65).tickSize(.05).instrumentType("OI").build();}
    @Test void selectsExactSideExpiryAndExchangeForBothIndices(){
        var day=LocalDate.of(2026,11,9);
        var a=option(1,"PE","10/11/2026",23000);var b=option(2,"CE","03/11/2026",23000);
        var c=option(3,"CE","10/11/2026",23000);var d=option(4,"CE","10/11/2026",23050);
        var rows=List.of(a,b,c,d,option(5,"CE","17/11/2026",23025));
        assertEquals(3,AdxDmiStrategy.contract(rows,"NIFTY","CE",23025,day).getScripCode());
        assertEquals(1,AdxDmiStrategy.contract(rows,"NIFTY","PE",23025,day).getScripCode());
        var sensex=option(9,"CE","12/11/2026",80000);sensex.setTradingSymbol("SENSEX");sensex.setExchange("BF");sensex.setLotSize(20);
        assertEquals(9,AdxDmiStrategy.contract(List.of(c,sensex),"SENSEX","CE",80010,day).getScripCode());
        assertThrows(IllegalStateException.class,()->AdxDmiStrategy.contract(List.of(c),"SENSEX","CE",80010,day));
        assertThrows(IllegalStateException.class,()->AdxDmiStrategy.contract(List.of(b),"NIFTY","CE",23000,day));
    }
}
