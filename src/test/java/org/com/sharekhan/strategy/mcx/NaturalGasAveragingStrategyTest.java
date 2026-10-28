package org.com.sharekhan.strategy.mcx;

import java.time.*;
import java.util.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.*;
import org.com.sharekhan.cache.QuoteCacheService;
import org.com.sharekhan.dto.*;
import org.com.sharekhan.entity.*;
import org.com.sharekhan.repository.*;
import org.com.sharekhan.service.TradeExecutionService;
import org.com.sharekhan.strategy.*;
import org.com.sharekhan.strategy.run.StrategyRunCoordinator;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class NaturalGasAveragingStrategyTest {
    final ScriptMasterRepository scripts=mock(ScriptMasterRepository.class);
    final BrokerCredentialsRepository brokers=mock(BrokerCredentialsRepository.class);
    final TriggerTradeRequestRepository requests=mock(TriggerTradeRequestRepository.class);
    final TriggeredTradeSetupRepository setups=mock(TriggeredTradeSetupRepository.class);
    final NaturalGasMarketData data=mock(NaturalGasMarketData.class);
    final QuoteCacheService quotes=mock(QuoteCacheService.class);
    final TradeExecutionService execution=mock(TradeExecutionService.class);
    final StrategySupport support=mock(StrategySupport.class);
    final StrategyRunCoordinator coordinator=mock(StrategyRunCoordinator.class);
    final NaturalGasAveragingStrategy strategy=new NaturalGasAveragingStrategy(scripts,brokers,requests,setups,data,quotes,execution,support,coordinator);
    final LocalDateTime now=NaturalGasAveragingSignalsTest.NOW;
    ScriptMasterEntity future,ce,pe;
    StrategyApplyRequest request;
    @BeforeEach void setup() {
        request=new StrategyApplyRequest();request.setTemplateId(NaturalGasAveragingStrategy.TEMPLATE);request.setUserId(1L);request.setBrokerCredentialsId(2L);
        future=NaturalGasContractsTest.row(1,"FUTC",null,"24/11/2026",0);future.setLotSize(1250);future.setTickSize(.1);
        ce=NaturalGasContractsTest.row(42,"OPTC","CE","20/11/2026",305);ce.setLotSize(1250);ce.setTickSize(.05);
        pe=NaturalGasContractsTest.row(43,"OPTC","PE","20/11/2026",295);pe.setLotSize(1250);pe.setTickSize(.05);
        when(brokers.findById(2L)).thenReturn(Optional.of(BrokerCredentialsEntity.builder().id(2L).appUserId(1L).brokerName("Simulator").active(true).build()));
        when(scripts.findByExchangeIgnoreCaseAndTradingSymbolIgnoreCase("MX","NATURALGAS")).thenReturn(List.of(future,ce,pe));
        when(data.shoonyaCandles(future,now)).thenReturn(NaturalGasAveragingSignalsTest.bars(true));
        when(coordinator.currentId()).thenReturn("B1");
        when(support.waiting(any(),anyString(),anyString())).thenAnswer(c->StrategyApplyResponse.builder().status("waiting").message(c.getArgument(2)).build());
        when(support.executeTriggeredTrade(any())).thenReturn(TriggerTradeRequestEntity.builder().id(11L).build());
        futureQuote(306.4);optionQuote(42,19.9,20);optionQuote(43,19.9,20);
    }
    void futureQuote(double p) {
        var instant=now.atZone(StrategySupport.MARKET_ZONE).toInstant();
        when(quotes.getSnapshot(1)).thenReturn(Optional.of(QuoteCacheService.QuoteSnapshot.builder().scripCode(1).lastTradedPrice(p).lastLtpAt(instant).ltpSource("SHAREKHAN_WS").ltpProviderIdentity("scrip:1").build()));
    }
    void optionQuote(int code,double bid,double ask) {
        var instant=now.atZone(StrategySupport.MARKET_ZONE).toInstant();
        when(quotes.getSnapshot(code)).thenReturn(Optional.of(QuoteCacheService.QuoteSnapshot.builder().scripCode(code).bestBid(bid).bestAsk(ask).lastBookAt(instant).coherentBook(true).bookSource("SHAREKHAN_WS").bookProviderIdentity("scrip:"+code).build()));
    }
    TriggeredTradeSetupEntity first(double premium) {
        var t=NaturalGasAveragingBookTest.leg(1,"B1",premium);t.setEntryAt(now.withMinute(0));t.setStrikePrice(305d);return t;
    }
    void pullback() {
        var rs=new ArrayList<>(NaturalGasAveragingSignalsTest.bars(true));var t=now.toLocalDate().atTime(11,0);
        rs.set(rs.size()-1,new StrategyCandle(t.toLocalDate(),t.toLocalTime(),306.35,306.4,305.7,306d,100L));
        when(data.shoonyaCandles(future,now)).thenReturn(rs);
    }
    @Test void initialAtmCallBuysOneLotAndLeavesPerLegTargetsUnset() {
        var response=strategy.apply(request,now);assertThat(response.getStatus()).isEqualTo("triggered");
        var trigger=response.getTriggerRequest();assertThat(trigger.getOptionType()).isEqualTo("CE");assertThat(trigger.getStrikePrice()).isEqualTo(305d);
        assertThat(trigger.getQuantity()).isEqualTo(1);assertThat(trigger.getLots()).isEqualTo(1);assertThat(request.getLots()).isEqualTo(4);
        assertThat(trigger.getTarget1()).isNull();assertThat(trigger.getStopLoss()).isNull();assertThat(trigger.getEntryPrice()).isEqualTo(20);
        assertThat(request.getRiskSettings().getAmountMode().name()).isEqualTo("TOTAL");
        assertThat(request.getRiskSettings().getProfitTarget()).isEqualByComparingTo("1250");
    }
    @Test void bearishTrendSelectsAnAtmPutButStillBuysTheOption() {
        when(data.shoonyaCandles(future,now)).thenReturn(NaturalGasAveragingSignalsTest.bars(false));futureQuote(293.6);
        var response=strategy.apply(request,now);assertThat(response.getStatus()).isEqualTo("triggered");
        assertThat(response.getTriggerRequest().getOptionType()).isEqualTo("PE");assertThat(response.getTriggerRequest().getAction()).isEqualTo("BUY");
    }
    @Test void additionKeepsSameContractEvenIfAnotherStrikeBecomesAtm() {
        pullback();var t=first(20.1);when(setups.findBySource(NaturalGasAveragingStrategy.SOURCE)).thenReturn(List.of(t));
        var nowAtm=NaturalGasContractsTest.row(44,"OPTC","CE","20/11/2026",310);nowAtm.setTickSize(.05);nowAtm.setLotSize(1250);
        when(scripts.findByExchangeIgnoreCaseAndTradingSymbolIgnoreCase("MX","NATURALGAS")).thenReturn(List.of(future,ce,pe,nowAtm));futureQuote(310);
        var response=strategy.apply(request,now);assertThat(response.getStatus()).isEqualTo("triggered");assertThat(response.getTriggerRequest().getStrikePrice()).isEqualTo(305d);
    }
    @Test void belowAveragePremiumAloneDoesNotQualifyAsPullback() {
        var t=first(20.1);when(setups.findBySource(NaturalGasAveragingStrategy.SOURCE)).thenReturn(List.of(t));
        assertThat(strategy.apply(request,now).getMessage()).contains("pullback");verify(support,never()).executeTriggeredTrade(any());
    }
    @Test void sameCandleCannotFillAnotherLotAfterRestart() {
        pullback();var p=TriggerTradeRequestEntity.builder().id(5L).appUserId(1L).brokerCredentialsId(2L)
                .createdAt(now.minusSeconds(1)).status(org.com.sharekhan.enums.TriggeredTradeStatus.REJECTED).build();
        when(requests.findNaturalGasDailyRequests(anyString(),anyLong(),anyLong(),any(),any())).thenReturn(List.of(p));
        assertThat(strategy.apply(request,now).getMessage()).contains("new five-minute");verify(support,never()).executeTriggeredTrade(any());
    }
    @Test void pendingSubmissionBlocksAnotherLot() {
        var p=TriggerTradeRequestEntity.builder().id(5L).appUserId(1L).brokerCredentialsId(2L)
                .createdAt(now.minusMinutes(10)).status(org.com.sharekhan.enums.TriggeredTradeStatus.ENTRY_SUBMITTING).build();
        when(requests.findNaturalGasDailyRequests(anyString(),anyLong(),anyLong(),any(),any())).thenReturn(List.of(p));
        assertThat(strategy.apply(request,now).getMessage()).contains("pending");verify(support,never()).executeTriggeredTrade(any());
    }
    @Test void capitalCapAndFourLotCeilingAreEnforcedBeforeSubmission() {
        optionQuote(42,49.9,50);assertThat(strategy.apply(request,now).getMessage()).contains("₹60,000");
        var legs=new ArrayList<TriggeredTradeSetupEntity>();for(int i=1;i<=4;i++){var t=first(20.5-i*.1);t.setId((long)i);legs.add(t);}
        optionQuote(42,19.9,20);when(setups.findBySource(NaturalGasAveragingStrategy.SOURCE)).thenReturn(legs);
        assertThat(strategy.apply(request,now).getMessage()).contains("four");verify(support,never()).executeTriggeredTrade(any());
    }
    @Test void noAddingOnTrendReversalOrSwitchingOptionTypeInsideBasket() {
        var t=first(20.1);when(setups.findBySource(NaturalGasAveragingStrategy.SOURCE)).thenReturn(List.of(t));
        when(data.shoonyaCandles(future,now)).thenReturn(NaturalGasAveragingSignalsTest.bars(false));futureQuote(293.6);
        assertThat(strategy.apply(request,now).getMessage()).contains("reversed");verify(support,never()).executeTriggeredTrade(any());
    }
    @Test void rejectsLiveForeignBrokerOversizeAndAlteredRiskSettings() {
        when(brokers.findById(2L)).thenReturn(Optional.of(BrokerCredentialsEntity.builder().appUserId(1L).brokerName("Sharekhan").build()));
        assertThatThrownBy(()->strategy.apply(request,now)).isInstanceOf(IllegalArgumentException.class);
        request.setLots(5);assertThatThrownBy(()->NaturalGasAveragingStrategy.validate(request)).isInstanceOf(IllegalArgumentException.class);
        request.setLots(4);request.getRiskSettings().setMaxLoss(new BigDecimal("3001"));assertThatThrownBy(()->NaturalGasAveragingStrategy.validate(request)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(execution);
    }
    @Test void staleFutureWrongIdentityAndStaleOptionBooksCannotEnter() {
        var instant=now.atZone(StrategySupport.MARKET_ZONE).toInstant();
        when(quotes.getSnapshot(42)).thenReturn(Optional.of(QuoteCacheService.QuoteSnapshot.builder().scripCode(42).bestBid(19.9).bestAsk(20d).lastBookAt(instant.minusSeconds(6)).coherentBook(true).bookSource("SHAREKHAN_WS").bookProviderIdentity("scrip:42").build()));
        assertThat(strategy.apply(request,now).getMessage()).contains("five seconds");verify(support,never()).executeTriggeredTrade(any());
    }
    @Test void newStrategyUsesCommoditySessionAndNeverCreatesEntryAfterCutoff() {
        assertThat(NaturalGasSession.isTemplate(NaturalGasAveragingStrategy.TEMPLATE)).isTrue();assertThat(NaturalGasSession.owns(NaturalGasAveragingStrategy.SOURCE)).isTrue();
        assertThat(strategy.apply(request,now.withHour(23)).getMessage()).contains("Outside");verifyNoInteractions(data,execution);
    }
}
