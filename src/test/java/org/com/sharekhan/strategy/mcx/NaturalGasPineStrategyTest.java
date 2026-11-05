package org.com.sharekhan.strategy.mcx;

import java.math.*;
import java.time.*;
import java.util.*;
import org.com.sharekhan.cache.QuoteCacheService;
import org.com.sharekhan.dto.*;
import org.com.sharekhan.entity.*;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.com.sharekhan.repository.*;
import org.com.sharekhan.service.TradeExecutionService;
import org.com.sharekhan.strategy.*;
import org.com.sharekhan.strategy.run.StrategyRiskSettings;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class NaturalGasPineStrategyTest {
    final ScriptMasterRepository scripts=mock(ScriptMasterRepository.class);
    final BrokerCredentialsRepository brokers=mock(BrokerCredentialsRepository.class);
    final TriggerTradeRequestRepository requests=mock(TriggerTradeRequestRepository.class);
    final TriggeredTradeSetupRepository setups=mock(TriggeredTradeSetupRepository.class);
    final NaturalGasMarketData data=mock(NaturalGasMarketData.class);
    final QuoteCacheService quotes=mock(QuoteCacheService.class);
    final TradeExecutionService execution=mock(TradeExecutionService.class);
    final StrategySupport support=mock(StrategySupport.class);
    final NaturalGasPinePolicy policy=new NaturalGasPinePolicy();
    final NaturalGasPineStrategy strategy=new NaturalGasPineStrategy(scripts,brokers,requests,setups,data,quotes,execution,support,new NaturalGasDataPolicy(NaturalGasDataMode.FULL_SESSION),policy);
    final StrategyApplyRequest r=new StrategyApplyRequest();
    final LocalDateTime now=NaturalGasPineSignalsTest.NOW;
    ScriptMasterEntity future=NaturalGasContractsTest.row(1,"FUTC",null,"24/11/2026",0);
    ScriptMasterEntity ce=NaturalGasContractsTest.row(2,"OPTC","CE","20/11/2026",300);
    ScriptMasterEntity pe=NaturalGasContractsTest.row(3,"OPTC","PE","20/11/2026",300);
    @BeforeEach void setup() {
        r.setTemplateId(NaturalGasPineStrategy.TEMPLATE);r.setSymbol("NATURALGAS");r.setUserId(1L);r.setBrokerCredentialsId(2L);r.setLots(1);
        var risk=new StrategyRiskSettings();risk.setMaxLoss(new BigDecimal("3000"));r.setRiskSettings(risk);
        future.setTickSize(.1);
        when(brokers.findById(2L)).thenReturn(Optional.of(BrokerCredentialsEntity.builder().id(2L).appUserId(1L).brokerName("Simulator").build()));
        when(scripts.findByExchangeIgnoreCaseAndTradingSymbolIgnoreCase("MX","NATURALGAS")).thenReturn(List.of(future,ce,pe));
        when(data.candles(1,now)).thenReturn(NaturalGasPineSignalsTest.candles(true));
        when(quotes.getSnapshot(1)).thenReturn(Optional.of(futures(304,0)));
        for(int code:List.of(2,3))when(quotes.getSnapshot(code)).thenReturn(Optional.of(QuoteCacheService.QuoteSnapshot.builder()
                .scripCode(code).bestBid(19.9).bestAsk(20d).coherentBook(true).lastBookAt(now.atZone(StrategySupport.MARKET_ZONE).toInstant())
                .bookSource("SHAREKHAN_WS").bookProviderIdentity("scrip:"+code).build()));
        when(support.waiting(any(),anyString(),anyString())).thenAnswer(a->StrategyApplyResponse.builder().status("waiting").message(a.getArgument(2)).build());
        when(support.executeTriggeredTrade(any())).thenReturn(TriggerTradeRequestEntity.builder().id(10L).build());
    }
    QuoteCacheService.QuoteSnapshot futures(double price,int age) {return QuoteCacheService.QuoteSnapshot.builder().scripCode(1).lastTradedPrice(price)
            .lastLtpAt(now.atZone(StrategySupport.MARKET_ZONE).toInstant().minusSeconds(age)).ltpSource("SHAREKHAN_WS").ltpProviderIdentity("scrip:1").build();}
    @Test void buysCeButStoresFuturesStopsTargetsAndExactUnderlying() {
        var result=strategy.apply(r,now);assertEquals("triggered",result.getStatus(),result.getMessage());
        var t=result.getTriggerRequest();assertEquals("BUY",t.getAction());assertEquals("CE",t.getOptionType());assertEquals(20,t.getEntryPrice());
        assertEquals(1,t.getSpotScripCode());assertFalse(t.getUseSpotForEntry());assertTrue(t.getUseSpotForSl());assertTrue(t.getUseSpotForTarget());
        assertTrue(t.getStopLoss()>290 && t.getStopLoss()<304);assertTrue(t.getTarget1()>304);assertFalse(t.getTslEnabled());
    }
    @Test void buysPeWithFuturesStopAboveAndTargetBelow() {
        when(data.candles(1,now)).thenReturn(NaturalGasPineSignalsTest.candles(false));when(quotes.getSnapshot(1)).thenReturn(Optional.of(futures(296,0)));
        var result=strategy.apply(r,now);assertEquals("triggered",result.getStatus(),result.getMessage());
        var t=result.getTriggerRequest();assertEquals("BUY",t.getAction());assertEquals("PE",t.getOptionType());assertTrue(t.getStopLoss()>296);assertTrue(t.getTarget1()<296);
    }
    @Test void bracketUsesFuturesAtrAndTickRatherThanOptionPremium() {
        var longLevels=NaturalGasPineStrategy.levels("CE",300,2,.1,policy);
        assertEquals(297,longLevels.stop(),1e-9);assertEquals(306,longLevels.target(),1e-9);
        var shortLevels=NaturalGasPineStrategy.levels("PE",300,2,.1,policy);
        assertEquals(303,shortLevels.stop(),1e-9);assertEquals(294,shortLevels.target(),1e-9);
    }
    @Test void rejectsForeignBrokerAndPremiumTargetControls() {
        when(brokers.findById(2L)).thenReturn(Optional.of(BrokerCredentialsEntity.builder().appUserId(99L).brokerName("Simulator").build()));
        assertThrows(IllegalArgumentException.class,()->strategy.apply(r,now));verifyNoInteractions(data,execution);
        r.getRiskSettings().setProfitTarget(BigDecimal.TEN);assertThrows(IllegalArgumentException.class,()->NaturalGasPineStrategy.validate(r));
    }
    @Test void noOrderWithStaleOrWrongContractQuotesOrDuringNews() {
        when(quotes.getSnapshot(1)).thenReturn(Optional.of(futures(304,16)));assertEquals("waiting",strategy.apply(r,now).getStatus());
        var wrong=QuoteCacheService.QuoteSnapshot.builder().lastTradedPrice(304d).lastLtpAt(now.atZone(StrategySupport.MARKET_ZONE).toInstant()).ltpSource("SHAREKHAN_WS").ltpProviderIdentity("scrip:999").build();when(quotes.getSnapshot(1)).thenReturn(Optional.of(wrong));
        assertEquals("waiting",strategy.apply(r,now).getStatus());verify(support,never()).executeTriggeredTrade(any());
        clearInvocations(data);assertEquals("waiting",strategy.apply(r,LocalDateTime.of(2026,11,5,21,0)).getStatus());verifyNoInteractions(data);
    }
    @Test void durablePositionsKeepDailyCapAfterRequestCleanup() {
        var rows=new ArrayList<TriggeredTradeSetupEntity>();
        for(int i=0;i<3;i++)rows.add(TriggeredTradeSetupEntity.builder().id((long)i).appUserId(1L).brokerCredentialsId(2L)
                .entryAt(now.minusHours(1)).exitedAt(now.minusMinutes(30)).status(TriggeredTradeStatus.EXITED_SUCCESS).actualEntryPrice(20d).build());
        when(setups.findBySource(NaturalGasPineStrategy.SOURCE)).thenReturn(rows);
        assertEquals("waiting",strategy.apply(r,now).getStatus());verifyNoInteractions(data,execution);
    }
    @Test void openOrUncertainPriorDayExposureBlocksEntry() {
        when(setups.findBySource(NaturalGasPineStrategy.SOURCE)).thenReturn(List.of(TriggeredTradeSetupEntity.builder()
                .appUserId(1L).brokerCredentialsId(2L).entryAt(now.minusDays(1)).actualEntryPrice(20d).status(TriggeredTradeStatus.FAILED).build()));
        assertEquals("waiting",strategy.apply(r,now).getStatus());verifyNoInteractions(data,execution);
    }
    @Test void reentryNeedsNewSignalAfterPriorAttempt() {
        when(requests.findNaturalGasDailyRequests(anyString(),anyLong(),anyLong(),any(),any())).thenReturn(List.of(TriggerTradeRequestEntity.builder()
                .id(7L).createdAt(now).status(TriggeredTradeStatus.REJECTED).build()));
        assertEquals("waiting",strategy.apply(r,now).getStatus());verify(support,never()).executeTriggeredTrade(any());
    }
    @Test void miniNeverFallsBackToStandardContractOrLotSize() {
        r.setSymbol("NATGASMINI");
        assertEquals("waiting",strategy.apply(r,now).getStatus());verify(support,never()).executeTriggeredTrade(any());
    }
    @Test void miniUsesItsOwnListedOptionAndMetadataWhenAvailable() {
        r.setSymbol("NATGASMINI");
        for(var row:List.of(future,ce,pe)){row.setTradingSymbol("NATGASMINI");row.setLotSize(250);}
        when(scripts.findByExchangeIgnoreCaseAndTradingSymbolIgnoreCase("MX","NATGASMINI")).thenReturn(List.of(future,ce,pe));
        var result=strategy.apply(r,now);assertEquals("triggered",result.getStatus(),result.getMessage());
        assertEquals("NATGASMINI",result.getTriggerRequest().getInstrument());assertEquals(1,result.getTriggerRequest().getQuantity());
        verify(scripts,never()).findByExchangeIgnoreCaseAndTradingSymbolIgnoreCase("MX","NATURALGAS");
    }
}
