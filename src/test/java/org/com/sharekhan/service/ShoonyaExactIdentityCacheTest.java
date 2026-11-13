package org.com.sharekhan.service;
import org.junit.jupiter.api.Test;
import org.com.sharekhan.config.ShoonyaProperties;
import org.com.sharekhan.auth.*;
import org.com.sharekhan.entity.*;
import java.util.*;
import static org.mockito.Mockito.*;
class ShoonyaExactIdentityCacheTest {
 @Test void preparationAvoidsRepeatedMasterScansButChangedContractIsResolvedAgain(){
  var master=mock(ShoonyaInstrumentMasterService.class);
  var service=new ShoonyaQuoteService(mock(ShoonyaProperties.class),mock(TokenStoreService.class),mock(BrokerAuthProviderRegistry.class),master);
  var script=ScriptMasterEntity.builder().scripCode(1).exchange("NF").tradingSymbol("NIFTY").expiry("29/12/2026").strikePrice(26450d).optionType("CE").lotSize(65).build();
  when(master.resolveScript(script)).thenReturn(Optional.of(ShoonyaInstrumentEntity.builder().exchange("NFO").token("1").tradingSymbol("NIFTY29DEC26C26450").build()));
  service.prepareExactQuote(script);service.prepareExactQuote(script);verify(master,times(1)).resolveScript(script);
  script.setStrikePrice(26500d);service.prepareExactQuote(script);verify(master,times(2)).resolveScript(script);
 }
}
