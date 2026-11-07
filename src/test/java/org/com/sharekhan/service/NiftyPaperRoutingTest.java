package org.com.sharekhan.service;

import org.com.sharekhan.dto.TriggerRequest;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.exception.InvalidTradeRequestException;
import org.com.sharekhan.strategy.nifty.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NiftyPaperRoutingTest {
    @Test void manualGenericEntryCannotSpoofThePaperSource(){
        var service=mock(TradeExecutionService.class,CALLS_REAL_METHODS);
        var request=new TriggerRequest();request.setSource(NiftyThreeCandleRules.SOURCE);request.setInstrument("NIFTY");request.setExchange("NF");
        assertThrows(InvalidTradeRequestException.class,()->service.executeTrade(request));
        assertThrows(InvalidTradeRequestException.class,()->service.executeTriggeredTrade(request));
    }
    @Test void genericCloseAllDelegatesPaperPositionsToItsOwnBidFillPath(){
        var service=mock(TradeExecutionService.class,CALLS_REAL_METHODS);var paper=mock(NiftyThreeCandlePaperBroker.class);
        ReflectionTestUtils.setField(service,"niftyThreeCandlePaper",paper);
        var t=TriggeredTradeSetupEntity.builder().id(3L).source(NiftyThreeCandleRules.SOURCE).build();
        service.squareOff(t,999999,"STRATEGY_CLOSE_ALL");verify(paper).close(3L,"STRATEGY_CLOSE_ALL");
    }
}
