package org.com.sharekhan.service;

import org.com.sharekhan.auth.TokenStoreService;
import org.com.sharekhan.config.SharekhanProperties;
import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.enums.Broker;
import org.com.sharekhan.repository.ScriptMasterRepository;
import org.com.sharekhan.util.CryptoService;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SharekhanHistoricalServiceTest {
    @Test
    void usesDocumentedIntervalAndFiltersReturnedDatesLocally() throws Exception {
        ScriptMasterRepository scripts = mock(ScriptMasterRepository.class);
        TokenStoreService tokens = mock(TokenStoreService.class);
        CryptoService crypto = mock(CryptoService.class);
        when(scripts.findByScripCode(20000)).thenReturn(ScriptMasterEntity.builder().exchange("NC").scripCode(20000).build());
        when(tokens.getFirstNonExpiredTokenInfo(Broker.SHAREKHAN)).thenReturn(new TokenStoreService.TokenInfo("test-token", "test-key"));
        when(crypto.decrypt("test-key")).thenReturn("test-key");
        SharekhanHistoricalService service = spy(new SharekhanHistoricalService(scripts, tokens, crypto, new SharekhanProperties()));
        JSONObject response = new JSONObject("""
                {"status":200,"message":"chart","data":[
                  {"tradeDate":"01/10/2026","tradeTime":"15:25:00","open":100,"high":102,"low":99,"close":101,"qty":1000},
                  {"tradeDate":"02/10/2026","tradeTime":"09:15:00","open":101,"high":103,"low":100,"close":102,"qty":1200}
                ]}
                """);
        doReturn(response).when(service).requestHistorical("NC", "20000", "5minute", "test-key", "test-token");
        var candles = service.getHistoricalCandles(20000, "5minute", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 1));
        assertThat(candles).hasSize(1);
        assertThat(candles.get(0).date()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(candles.get(0).time()).isEqualTo(LocalTime.of(15, 25));
        assertThat(candles.get(0).close()).isEqualTo(101);
        verify(service).requestHistorical("NC", "20000", "5minute", "test-key", "test-token");
    }
    @Test
    void convertsSharekhanFiveMinuteEndStampsToStartsWithoutChangingAlreadyAlignedBars() throws Exception {
        ScriptMasterRepository scripts = mock(ScriptMasterRepository.class);
        TokenStoreService tokens = mock(TokenStoreService.class);
        CryptoService crypto = mock(CryptoService.class);
        when(scripts.findByScripCode(20000)).thenReturn(ScriptMasterEntity.builder().exchange("NC").scripCode(20000).build());
        when(tokens.getFirstNonExpiredTokenInfo(Broker.SHAREKHAN)).thenReturn(new TokenStoreService.TokenInfo("test-token", "test-key"));
        when(crypto.decrypt("test-key")).thenReturn("test-key");
        SharekhanHistoricalService service = spy(new SharekhanHistoricalService(scripts, tokens, crypto, new SharekhanProperties()));
        JSONObject response = new JSONObject("""
                {"status":200,"data":[
                  {"tradeDate":"01/10/2026","tradeTime":"09:19:59","open":100,"high":102,"low":99,"close":101},
                  {"tradeDate":"01/10/2026","tradeTime":"09:24:59","open":101,"high":103,"low":100,"close":102},
                  {"tradeDate":"01/10/2026","tradeTime":"15:15:00","open":102,"high":104,"low":101,"close":103}
                ]}
                """);
        doReturn(response).when(service).requestHistorical("NC", "20000", "5minute", "test-key", "test-token");
        var candles = service.getRecentHistoricalCandles(20000, "5minute");
        assertThat(candles).extracting(SharekhanHistoricalService.HistoricalCandle::time)
                .containsExactly(LocalTime.of(9,15),LocalTime.of(9,20),LocalTime.of(15,15));
        assertThat(candles).extracting(SharekhanHistoricalService.HistoricalCandle::date)
                .containsOnly(LocalDate.of(2026,10,1));
        assertThat(candles.get(0).close()).isEqualTo(101);
        doReturn(response).when(service).requestHistorical("NC", "20000", "15minute", "test-key", "test-token");
        assertThat(service.getRecentHistoricalCandles(20000,"15minute").get(0).time()).isEqualTo(LocalTime.of(9,19,59));
    }

}
