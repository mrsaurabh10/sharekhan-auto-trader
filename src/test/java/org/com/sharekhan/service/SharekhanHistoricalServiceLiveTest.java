package org.com.sharekhan.service;

import org.com.sharekhan.auth.TokenStoreService;
import org.com.sharekhan.auth.TokenStoreService.TokenInfo;
import org.com.sharekhan.config.SharekhanProperties;
import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.enums.Broker;
import org.com.sharekhan.repository.ScriptMasterRepository;
import org.com.sharekhan.util.CryptoService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.OptionalDouble;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Live test that hits the Sharekhan Historical API.
 *
 * <p>Provide the following environment variables before running:
 * <ul>
 *     <li>{@code SHAREKHAN_HISTORICAL_API_KEY}</li>
 *     <li>{@code SHAREKHAN_HISTORICAL_ACCESS_TOKEN}</li>
 *     <li>{@code SHAREKHAN_HISTORICAL_EXCHANGE}</li>
 *     <li>{@code SHAREKHAN_HISTORICAL_SCRIP_CODE}</li>
 * </ul>
 *
 * Optionally set {@code SHAREKHAN_HISTORICAL_SYMBOL} when you want the script master lookup
 * to include a trading symbol hint.
 */
@Tag("live")
class SharekhanHistoricalServiceLiveTest {

    @Test
    void fetchesOpeningPriceFromSharekhanHistoricalApi() {
        String apiKey = System.getenv("SHAREKHAN_HISTORICAL_API_KEY");
        String accessToken = System.getenv("SHAREKHAN_HISTORICAL_ACCESS_TOKEN");
        String exchange = System.getenv("SHAREKHAN_HISTORICAL_EXCHANGE");
        String scripCodeRaw = System.getenv("SHAREKHAN_HISTORICAL_SCRIP_CODE");
        String symbol = System.getenv("SHAREKHAN_HISTORICAL_SYMBOL");

        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(), "SHAREKHAN_HISTORICAL_API_KEY must be set");
        Assumptions.assumeTrue(accessToken != null && !accessToken.isBlank(), "SHAREKHAN_HISTORICAL_ACCESS_TOKEN must be set");
        Assumptions.assumeTrue(exchange != null && !exchange.isBlank(), "SHAREKHAN_HISTORICAL_EXCHANGE must be set");
        Assumptions.assumeTrue(scripCodeRaw != null && !scripCodeRaw.isBlank(), "SHAREKHAN_HISTORICAL_SCRIP_CODE must be set");

        int scripCode = Integer.parseInt(scripCodeRaw.trim());

        ScriptMasterRepository scriptRepo = mock(ScriptMasterRepository.class);
        ScriptMasterEntity script = ScriptMasterEntity.builder()
                .scripCode(scripCode)
                .exchange(exchange)
                .tradingSymbol(symbol)
                .build();
        when(scriptRepo.findByScripCode(scripCode)).thenReturn(script);

        TokenStoreService tokenStore = mock(TokenStoreService.class);
        when(tokenStore.getFirstNonExpiredTokenInfo(Broker.SHAREKHAN))
                .thenReturn(new TokenInfo(accessToken, apiKey));
        when(tokenStore.getAccessToken(Broker.SHAREKHAN)).thenReturn(accessToken);

        CryptoService crypto = mock(CryptoService.class);
        when(crypto.decrypt(any())).thenAnswer(invocation -> invocation.getArgument(0));

        SharekhanProperties props = new SharekhanProperties();
        props.setApiKey(apiKey);

        SharekhanHistoricalService service = new SharekhanHistoricalService(scriptRepo, tokenStore, crypto, props);

        OptionalDouble firstFetch = service.getTodayOpenPrice(scripCode);
        assertThat(firstFetch).isPresent();
        assertThat(firstFetch.getAsDouble()).isGreaterThan(0.0);

        // Ensure we are looking at today's data
        OptionalDouble todaysFetch = service.getTodayOpenPrice(scripCode);
        assertThat(todaysFetch).isPresent();
        assertThat(todaysFetch.getAsDouble()).isEqualTo(firstFetch.getAsDouble());

        // Cached invocation should not reach out to token store again.
        clearInvocations(tokenStore);
        OptionalDouble cachedFetch = service.getTodayOpenPrice(scripCode);
        assertThat(cachedFetch).isPresent();
        assertThat(cachedFetch.getAsDouble()).isEqualTo(firstFetch.getAsDouble());
        verify(tokenStore, times(0)).getFirstNonExpiredTokenInfo(eq(Broker.SHAREKHAN));
    }
}
