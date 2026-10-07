package org.com.sharekhan.strategy;

import org.com.sharekhan.dto.*;
import org.com.sharekhan.entity.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.assertThat;

class SpotAtrPreviousDayBigTradePlusSellStrategyTest {
    @Test void buildsThreeCashShortsWithPerStockQuantityAndBearishQualification() {
        var support=mock(StrategySupport.class);
        var qualification=mock(AtrPreviousDayBreakoutQualificationService.class);
        var strategy=new SpotAtrPreviousDayBigTradePlusSellStrategy(support,qualification);
        var request=new StrategyApplyRequest(); request.setLots(10); request.setSymbol("SBIN"); request.setUserId(1L);
        var spot=new ScriptMasterEntity(); spot.setTradingSymbol("SBIN");
        when(support.resolveSpotScript("SBIN")).thenReturn(spot);
        when(support.roundPrice(anyDouble())).thenAnswer(a -> a.getArgument(0));
        when(qualification.qualify(eq(spot),eq("PE"),eq(1L),any())).thenReturn(
                Fno925EntryQualificationService.Qualification.qualified(new Fno925EntryQualificationService.Signal(100,102,1,110,100,null,"test")));
        when(support.createPendingTradeRequest(any())).thenReturn(new TriggerTradeRequestEntity());
        var result=strategy.apply(request);
        var captor=ArgumentCaptor.forClass(TriggerRequest.class);
        verify(support,times(3)).createPendingTradeRequest(captor.capture());
        assertThat(result.getDirection()).isEqualTo("SELL");
        assertThat(captor.getAllValues()).extracting(TriggerRequest::getQuantity).containsExactly(3,3,4);
        assertThat(captor.getAllValues()).extracting(TriggerRequest::getTarget1).containsExactly(97d,95d,94d);
        for(var leg:captor.getAllValues()) {
            assertThat(leg.getStopLoss()).isEqualTo(102d);
            assertThat(leg.getIntraday()).isTrue();
            assertThat(leg.getOptionType()).isNull();
        }
    }
}
