package org.com.sharekhan.service.broker;

import java.util.Map;
import org.com.sharekhan.auth.TokenStoreService;
import org.com.sharekhan.dto.BrokerContext;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.service.MStockInstrumentResolver;
import org.com.sharekhan.util.CryptoService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class MStockBrokerServiceProductTest {
    TokenStoreService tokens=mock(TokenStoreService.class);
    MStockInstrumentResolver instruments=mock(MStockInstrumentResolver.class);
    MStockBrokerService service=new MStockBrokerService(tokens,mock(CryptoService.class),instruments);

    @Test void shortCashEntryAndCoverKeepMisProduct() {
        var trade=TriggeredTradeSetupEntity.builder().entryAction("SELL").symbol("TEST").exchange("NC").quantity(10L).intraday(true).build();
        assertThat(service.placeOrder(trade,new BrokerContext(null,null,null,"MStock",7L),100).isSuccess()).isFalse();
        assertThat(trade.getBrokerExecutionProduct()).isEqualTo("MIS");
        var instrument=ReflectionTestUtils.invokeMethod(service,"resolveInstrumentIdentity",trade);
        Map<String,String> form=ReflectionTestUtils.invokeMethod(service,"buildBaseOrderForm",trade,instrument,org.com.sharekhan.util.TradeSide.exit(trade),"LIMIT",95d,null);
        assertThat(form).containsEntry("product","MIS").containsEntry("transaction_type","BUY");
    }
    @Test void sameDayEntryUsesDeliveryAndBothExitAndModifyFormsRetainProduct() {
        for(String exchange:new String[]{"NC","NF"}) {
            var trade=TriggeredTradeSetupEntity.builder().symbol("TEST").exchange(exchange).quantity(10L).intraday(true).build();
            // Missing mock credentials stop before HTTP; the real entry form is built first.
            assertThat(service.placeOrder(trade,new BrokerContext(null,null,null,"MStock",7L),100).isSuccess()).isFalse();
            assertThat(trade.getBrokerExecutionProduct()).isEqualTo(exchange.equals("NF")?"NRML":"CNC");
            assertThat(trade.getIntraday()).isTrue();
            var instrument=ReflectionTestUtils.invokeMethod(service,"resolveInstrumentIdentity",trade);
            for(String side:new String[]{"BUY","SELL"}) {
                Map<String,String> form=ReflectionTestUtils.invokeMethod(service,"buildBaseOrderForm",trade,instrument,side,"LIMIT",100d,null);
                assertThat(form).containsEntry("product",exchange.equals("NF")?"NRML":"CNC");
            }
            trade.setBrokerExecutionProduct(null);
            Map<String,String> legacy=ReflectionTestUtils.invokeMethod(service,"buildBaseOrderForm",trade,instrument,"SELL","LIMIT",100d,null);
            assertThat(legacy).containsEntry("product","MIS");
        }
    }
    @Test void bigTradePlusIsRejectedBeforeAuthOrInstrumentLookup() {
        var trade=TriggeredTradeSetupEntity.builder().brokerProductType("BIGTRADEPLUS").quantity(10L).build();
        assertThat(service.placeOrder(trade,null,100).getRejectionReason()).contains("require Sharekhan");
        assertThat(service.placeExitOrder(trade,null,100).isSuccess()).isFalse();
        assertThat(service.modifyEntryOrder(trade,null,"12345",100).isSuccess()).isFalse();
        service.cancelEntryOrder(trade,null,"12345");
        verifyNoInteractions(tokens,instruments);
    }
}
