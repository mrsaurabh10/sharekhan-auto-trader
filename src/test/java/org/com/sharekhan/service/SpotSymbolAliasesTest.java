package org.com.sharekhan.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SpotSymbolAliasesTest {

    @Test
    void resolvesBankNiftyToNiftyBankSpotAlias() {
        assertThat(SpotSymbolAliases.candidates("BANKNIFTY"))
                .containsExactly("BANKNIFTY", "NiftyBank");
    }

    @Test
    void keepsUnknownSymbolsUnchanged() {
        assertThat(SpotSymbolAliases.candidates("NIFTY"))
                .containsExactly("NIFTY");
    }
}
