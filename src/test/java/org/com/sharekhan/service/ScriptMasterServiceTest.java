package org.com.sharekhan.service;

import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.repository.ScriptMasterRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScriptMasterServiceTest {

    private final ScriptMasterRepository repository = mock(ScriptMasterRepository.class);
    private final ScriptMasterService service = new ScriptMasterService(repository);

    @Test
    void getInstrumentsForExchangeReturnsAlphabeticallySortedSymbols() {
        when(repository.findByExchange("NF")).thenReturn(List.of(
                script("TCS"),
                script("ADANIENT"),
                script("INFY"),
                script("ADANIENT"),
                script("axisbank")
        ));

        List<String> instruments = service.getInstrumentsForExchange("NF");

        assertThat(instruments).containsExactly("ADANIENT", "axisbank", "INFY", "TCS");
    }

    @Test
    void getInstrumentsForSpotExchangeReturnsAlphabeticallySortedSymbols() {
        when(repository.findByExchangeAndStrikePriceIsNullAndExpiryIsNullIgnoreCase("NC"))
                .thenReturn(List.of(
                        script("RELIANCE"),
                        script("BAJFINANCE"),
                        script("ASHOKLEY")
                ));

        List<String> instruments = service.getInstrumentsForExchange("nc");

        assertThat(instruments).containsExactly("ASHOKLEY", "BAJFINANCE", "RELIANCE");
    }

    private ScriptMasterEntity script(String tradingSymbol) {
        return ScriptMasterEntity.builder()
                .tradingSymbol(tradingSymbol)
                .build();
    }
}
