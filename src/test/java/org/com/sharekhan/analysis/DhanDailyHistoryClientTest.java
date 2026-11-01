package org.com.sharekhan.analysis;
import org.junit.jupiter.api.Test;
import org.com.sharekhan.auth.AccessTokenPersistenceService;
import org.com.sharekhan.repository.BrokerCredentialsRepository;
import org.com.sharekhan.entity.*;
import org.com.sharekhan.dhan.DhanInstrumentResolver;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import org.json.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class DhanDailyHistoryClientTest {
    final AccessTokenPersistenceService tokens=mock(AccessTokenPersistenceService.class);final BrokerCredentialsRepository credentials=mock(BrokerCredentialsRepository.class);final DhanInstrumentResolver master=mock(DhanInstrumentResolver.class);
    final LocalDate day=LocalDate.of(2026,10,30);final StockDailyHistoryStore.Member member=new StockDailyHistoryStore.Member("TCS","INE467B01029",day);
    final StockDailyHistoryStore.Saved saved=new StockDailyHistoryStore.Saved("TCS","11536",BigDecimal.valueOf(.1),Instant.now(),List.of());
    @Test void unconfiguredAccountMakesNoCredentialOrNetworkAttempt(){var client=new DhanDailyHistoryClient(tokens,credentials,master,0);assertThatThrownBy(()->client.fetch(member,saved,day,day)).hasMessageContaining("Configure");verifyNoInteractions(tokens,credentials,master);}
    @Test void usesOnlyExplicitAccountDecryptedTokenAndVerifiedIdentity()throws Exception {
        when(credentials.findById(77L)).thenReturn(Optional.of(BrokerCredentialsEntity.builder().brokerName("Dhan").active(true).build()));
        when(tokens.findLatestByBrokerAndBrokerCredentialsId("Dhan",77L)).thenReturn(Optional.of(AccessTokenEntity.builder().token("decrypted-by-token-service").expiry(Instant.now().plusSeconds(3600)).build()));
        when(master.cashIdentity("NC","TCS")).thenReturn(new DhanInstrumentResolver.CashIdentity(new DhanInstrumentResolver.Instrument("NSE_EQ","11536",1,BigDecimal.valueOf(.1),1000),"INE467B01029"));
        var client=new DhanDailyHistoryClient(tokens,credentials,master,77){@Override String request(String token,String body){assertThat(token).isEqualTo("decrypted-by-token-service");var request=new JSONObject(body);assertThat(request.getString("securityId")).isEqualTo("11536");assertThat(request.getString("toDate")).isEqualTo("2026-10-31");return new JSONObject().put("timestamp",new JSONArray().put(day.atStartOfDay(StockAnalysisService.IST).toEpochSecond())).put("open",new JSONArray().put(100)).put("high",new JSONArray().put(102)).put("low",new JSONArray().put(99)).put("close",new JSONArray().put(101)).put("volume",new JSONArray().put(1000)).toString();}};
        assertThat(client.fetch(member,saved,day,day).candles()).hasSize(1);verify(tokens).findLatestByBrokerAndBrokerCredentialsId("Dhan",77L);verify(credentials).findById(77L);
        assertThatThrownBy(()->client.fetch(new StockDailyHistoryStore.Member("TCS","INE000000001",day),saved,day,day)).hasMessageContaining("identity changed");
    }
}
