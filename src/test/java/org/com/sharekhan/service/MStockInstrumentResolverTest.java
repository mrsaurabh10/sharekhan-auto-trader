package org.com.sharekhan.service;

import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.entity.MStockInstrumentEntity;
import org.com.sharekhan.repository.MStockInstrumentRepository;
import org.com.sharekhan.repository.ScriptMasterRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MStockInstrumentResolverTest {

    private final ScriptMasterRepository scriptMasterRepository = mock(ScriptMasterRepository.class);
    private final ScriptMasterService scriptMasterService = mock(ScriptMasterService.class);
    private final MStockInstrumentRepository mStockInstrumentRepository = mock(MStockInstrumentRepository.class);

    private MStockInstrumentResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new MStockInstrumentResolver(scriptMasterRepository, scriptMasterService, mStockInstrumentRepository);
        when(mStockInstrumentRepository.findByExchangeIgnoreCaseAndNameIgnoreCase(anyString(), anyString()))
                .thenReturn(List.of());
        when(mStockInstrumentRepository.findByInstrumentKey(anyString())).thenReturn(Optional.empty());
        when(mStockInstrumentRepository.findByExchangeAndTradingSymbol(anyString(), anyString())).thenReturn(Optional.empty());
        when(mStockInstrumentRepository.count()).thenReturn(100L);
    }

    @Test
    void resolvesMStockIndexLtpKeysUsingDisplayNamesAcceptedByMStock() {
        assertThat(resolver.resolveInstrumentKey(spotScript(20000, "NIFTY", "NC")))
                .contains("NSE:Nifty 50");
        assertThat(resolver.resolveInstrumentKey(spotScript(26009, "BANKNIFTY", "NC")))
                .contains("NSE:Nifty Bank");
    }

    @Test
    void fallsBackToNseEquityKeyWhenMStockMasterIsMissingSpotRows() {
        assertThat(resolver.resolveInstrumentKey(spotScript(1333, "HDFCBANK", "NC")))
                .contains("NSE:HDFCBANK-EQ");
        assertThat(resolver.resolveInstrumentKey(spotScript(4963, "ICICIBANK", "NC")))
                .contains("NSE:ICICIBANK-EQ");
        assertThat(resolver.resolveInstrumentKey(spotScript(4668, "BANKBARODA", "NC")))
                .contains("NSE:BANKBARODA-EQ");
        assertThat(resolver.resolveInstrumentKey(spotScript(3426, "TATAPOWER", "NC")))
                .contains("NSE:TATAPOWER-EQ");
        assertThat(resolver.resolveInstrumentKey(spotScript(3506, "TITAN", "NC")))
                .contains("NSE:TITAN-EQ");
        assertThat(resolver.resolveInstrumentKey(spotScript(14299, "PFC", "NC")))
                .contains("NSE:PFC-EQ");
    }

    @Test
    void keepsHeuristicSpotFallbackCachedEvenWhenMasterIsPopulated() {
        ScriptMasterEntity hdfcBank = spotScript(1333, "HDFCBANK", "NC");
        when(scriptMasterRepository.findByScripCode(1333)).thenReturn(hdfcBank);

        assertThat(resolver.resolveInstrumentKey(1333)).contains("NSE:HDFCBANK-EQ");

        clearInvocations(mStockInstrumentRepository);
        assertThat(resolver.resolveInstrumentKey(1333)).contains("NSE:HDFCBANK-EQ");

        verify(mStockInstrumentRepository, never()).findByInstrumentKey(anyString());
    }

    @Test
    void rejectsSpotLikeAttributeMatchForDerivativeAndResolvesByExactOptionSymbol() {
        ScriptMasterEntity sensexOption = ScriptMasterEntity.builder()
                .scripCode(1127917)
                .tradingSymbol("SENSEX")
                .exchange("BF")
                .instrumentType("IO")
                .optionType("PE")
                .strikePrice(74000.0)
                .expiry("04/06/2026")
                .build();

        MStockInstrumentEntity looseNameMatch = MStockInstrumentEntity.builder()
                .instrumentToken(1L)
                .instrumentKey("BFO:SENSEX")
                .tradingSymbol("SENSEX")
                .name("SENSEX")
                .exchange("BFO")
                .instrumentType("IDX")
                .fetchedAt(java.time.LocalDateTime.now())
                .build();

        MStockInstrumentEntity exactDerivative = MStockInstrumentEntity.builder()
                .instrumentToken(2L)
                .instrumentKey("BFO:SENSEX04JUN2674000PE")
                .tradingSymbol("SENSEX04JUN2674000PE")
                .name("SENSEX")
                .exchange("BFO")
                .instrumentType("PE")
                .fetchedAt(java.time.LocalDateTime.now())
                .build();

        when(mStockInstrumentRepository.findByExchangeIgnoreCaseAndNameIgnoreCase("BFO", "SENSEX"))
                .thenReturn(List.of(looseNameMatch));
        when(mStockInstrumentRepository.findByInstrumentKey("BFO:SENSEX04JUN2674000PE"))
                .thenReturn(Optional.of(exactDerivative));

        assertThat(resolver.resolveInstrumentKey(sensexOption))
                .contains("BFO:SENSEX04JUN2674000PE");
    }

    private ScriptMasterEntity spotScript(Integer scripCode, String tradingSymbol, String exchange) {
        return ScriptMasterEntity.builder()
                .scripCode(scripCode)
                .tradingSymbol(tradingSymbol)
                .exchange(exchange)
                .instrumentType("EQ")
                .build();
    }
}
