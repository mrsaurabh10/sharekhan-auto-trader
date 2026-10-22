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
                .instrumentType("PE").expiry("2026-06-04").strike(74000d)
                .fetchedAt(java.time.LocalDateTime.now())
                .build();

        when(mStockInstrumentRepository.findByExchangeIgnoreCaseAndNameIgnoreCase("BFO", "SENSEX"))
                .thenReturn(List.of(looseNameMatch));
        when(mStockInstrumentRepository.findByInstrumentKey("BFO:SENSEX04JUN2674000PE"))
                .thenReturn(Optional.of(exactDerivative));

        assertThat(resolver.resolveInstrumentKey(sensexOption))
                .contains("BFO:SENSEX04JUN2674000PE");
    }

    @Test
    void rejectsWrongExpiryStrikeSideAndUnderlyingAcrossAttributeAndPatternLookups() {
        var script = octoberSensex();
        for (String fault : List.of("expiry", "strike", "side", "underlying", "missingExpiry", "missingUnderlying")) {
            var candidate = octoberContract();
            switch (fault) {
                case "expiry" -> candidate.setExpiry("2026-08-13");
                case "strike" -> candidate.setStrike(73300d);
                case "side" -> candidate.setInstrumentType("PE");
                case "underlying" -> candidate.setName("BANKEX");
                case "missingExpiry" -> candidate.setExpiry(null);
                case "missingUnderlying" -> candidate.setName(null);
            }
            when(mStockInstrumentRepository.findByExchangeIgnoreCaseAndNameIgnoreCase("BFO", "SENSEX"))
                    .thenReturn(List.of(candidate));
            when(mStockInstrumentRepository.findByInstrumentKey(candidate.getInstrumentKey()))
                    .thenReturn(Optional.of(candidate));
            when(mStockInstrumentRepository.findByExchangeAndTradingSymbolPattern("BFO", "SENSEX%"))
                    .thenReturn(List.of(candidate));
            assertThat(resolver.resolveInstrumentKey(script)).as(fault).isEmpty();
        }
    }

    @Test
    void patternLookupSkipsExpiredFirstRowAndSelectsExactContract() {
        var expired = octoberContract(); expired.setExpiry("2026-08-13");
        expired.setInstrumentKey("BFO:SENSEX2681373200CE");
        var correct = octoberContract();
        when(mStockInstrumentRepository.findByExchangeAndTradingSymbolPattern("BFO", "SENSEX%"))
                .thenReturn(List.of(expired, correct));
        when(mStockInstrumentRepository.findByInstrumentKey(correct.getInstrumentKey())).thenReturn(Optional.of(correct));
        assertThat(resolver.resolveInstrumentKey(octoberSensex())).contains(correct.getInstrumentKey());
    }

    @Test
    void cachedDerivativeMustStillMatchTheCurrentExpiryAndMasterMetadata() {
        var script = octoberSensex(); var contract = octoberContract();
        when(scriptMasterRepository.findByScripCode(1124326)).thenReturn(script);
        when(mStockInstrumentRepository.findByExchangeIgnoreCaseAndNameIgnoreCase("BFO", "SENSEX"))
                .thenReturn(List.of(contract));
        when(mStockInstrumentRepository.findByInstrumentKey(contract.getInstrumentKey())).thenReturn(Optional.of(contract));
        assertThat(resolver.resolveInstrumentKey(1124326)).contains(contract.getInstrumentKey());
        contract.setExpiry("2026-08-13");
        assertThat(resolver.resolveInstrumentKey(1124326)).isEmpty();
    }

    @Test
    void rejectsStaleExactSymbolRowAndUnverifiedDerivativeHeuristic() {
        var contract = octoberContract(); contract.setExpiry("2026-08-13");
        when(mStockInstrumentRepository.findByInstrumentKey(contract.getInstrumentKey())).thenReturn(Optional.of(contract));
        assertThat(resolver.resolveInstrumentKey(octoberSensex())).isEmpty();
        when(mStockInstrumentRepository.findByInstrumentKey(anyString())).thenReturn(Optional.empty());
        when(mStockInstrumentRepository.count()).thenReturn(0L);
        assertThat(resolver.resolveInstrumentKey(octoberSensex())).isEmpty();
    }

    private ScriptMasterEntity octoberSensex() {
        return ScriptMasterEntity.builder().scripCode(1124326).tradingSymbol("SENSEX").exchange("BF")
                .instrumentType("OI").optionType("CE").strikePrice(73200d).expiry("22/10/2026").build();
    }

    private MStockInstrumentEntity octoberContract() {
        return MStockInstrumentEntity.builder().instrumentKey("BFO:SENSEX22OCT2673200CE")
                .tradingSymbol("SENSEX22OCT2673200CE").name("SENSEX").exchange("BFO")
                .instrumentType("CE").strike(73200d).expiry("2026-10-22").build();
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
