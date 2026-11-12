package org.com.sharekhan.strategy.batman;
import org.junit.jupiter.api.Test;
import org.com.sharekhan.service.TradeExecutionService;
import org.com.sharekhan.dto.TriggerRequest;
import org.com.sharekhan.strategy.run.StrategyRunCoordinator;
import org.springframework.test.util.ReflectionTestUtils;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class BatmanExecutionAdmissionTest{
 TriggerRequest order(){var r=new TriggerRequest();r.setSource("batman:claim:2");r.setAction("SELL");r.setExchange("NF");r.setOptionType("CE");r.setIntraday(false);r.setTslEnabled(false);return r;}
 @Test void sourceSpoofingDoesNotEnableOptionShorts(){var execution=mock(TradeExecutionService.class,CALLS_REAL_METHODS);assertThatThrownBy(()->ReflectionTestUtils.invokeMethod(execution,"normalizedEntryAction",order())).hasMessageContaining("server-owned");}
 @Test void onlyCurrentScopedBatmanContextAllowsCarryOptionLegs(){
  var execution=mock(TradeExecutionService.class,CALLS_REAL_METHODS);var context=mock(StrategyRunCoordinator.class);ReflectionTestUtils.setField(execution,"runCoordinator",context);
  when(context.ownsBatmanEntry(any())).thenReturn(true);var r=order();assertThat((String)ReflectionTestUtils.invokeMethod(execution,"normalizedEntryAction",r)).isEqualTo("SELL");
  r.setIntraday(true);assertThatThrownBy(()->ReflectionTestUtils.invokeMethod(execution,"normalizedEntryAction",r)).hasMessageContaining("carry-forward");
  r.setIntraday(false);r.setSource("user-ui");assertThatThrownBy(()->ReflectionTestUtils.invokeMethod(execution,"normalizedEntryAction",r)).hasMessageContaining("intraday cash");
 }
}
