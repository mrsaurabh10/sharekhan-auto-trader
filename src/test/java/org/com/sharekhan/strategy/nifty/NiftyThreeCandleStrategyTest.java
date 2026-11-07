package org.com.sharekhan.strategy.nifty;

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

class NiftyThreeCandleStrategyTest {
    @Test void configurationNormalizesTheAgreedTrialAndRejectsLiveOrOtherOwners(){
        var brokers=mock(BrokerCredentialsRepository.class);var feeds=mock(TradeExecutionService.class);
        var strategy=new NiftyThreeCandleStrategy(brokers,mock(ScriptMasterRepository.class),mock(StrategySupport.class),mock(NiftyThreeCandleMarketData.class),
                mock(QuoteCacheService.class),feeds,mock(NiftyThreeCandlePaperBroker.class),mock(NseMarketCalendar.class));
        var r=new StrategyApplyRequest();r.setUserId(1L);r.setBrokerCredentialsId(2L);
        when(brokers.findById(2L)).thenReturn(Optional.of(BrokerCredentialsEntity.builder().appUserId(1L).brokerName("Simulator").active(true).build()));
        strategy.validateConfiguration(r);assertEquals("NIFTY",r.getSymbol());assertEquals(1,r.getLots());assertEquals(5000,r.getRiskSettings().getMaxLoss().intValue());
        when(brokers.findById(2L)).thenReturn(Optional.of(BrokerCredentialsEntity.builder().appUserId(1L).brokerName("Sharekhan").active(true).build()));
        assertThrows(IllegalArgumentException.class,()->strategy.validateConfiguration(r));
        when(brokers.findById(2L)).thenReturn(Optional.of(BrokerCredentialsEntity.builder().appUserId(9L).brokerName("Simulator").active(true).build()));
        assertThrows(IllegalArgumentException.class,()->strategy.validateConfiguration(r));
        r.setLots(2);assertThrows(IllegalArgumentException.class,()->NiftyThreeCandleStrategy.validate(r));verifyNoInteractions(feeds);
    }
    static ScriptMasterEntity option(int code,String side,String expiry,double strike){return ScriptMasterEntity.builder().scripCode(code).tradingSymbol("NIFTY")
            .exchange("NF").optionType(side).expiry(expiry).strikePrice(strike).lotSize(65).tickSize(.05).instrumentType("OI").build();}
    @Test void atmUsesTheExactSideNearestUnexpiredExpiryAndDeterministicStrikeTie(){
        var day=LocalDate.of(2026,11,9);
        var rows=List.of(option(1,"PE","10/11/2026",23000),option(2,"CE","03/11/2026",23000),
                option(3,"CE","10/11/2026",23000),option(4,"CE","10/11/2026",23050),option(5,"CE","17/11/2026",23025));
        assertEquals(3,NiftyThreeCandleStrategy.contract(rows,"CE",23025,day).getScripCode());
        assertEquals(1,NiftyThreeCandleStrategy.contract(rows,"PE",23025,day).getScripCode());
        assertThrows(IllegalStateException.class,()->NiftyThreeCandleStrategy.contract(List.of(rows.get(1)),"CE",23000,day));
    }
}
