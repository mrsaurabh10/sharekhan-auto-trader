package org.com.sharekhan.service;

import org.com.sharekhan.auth.TokenStoreService;
import org.com.sharekhan.repository.ScriptMasterRepository;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ScriptMasterCacheServiceTest {
    private final ScriptMasterCacheService service = new ScriptMasterCacheService(
            mock(ScriptMasterRepository.class), mock(TokenStoreService.class));

    @Test
    void importsSharekhanMaxhealthTickSize() throws Throwable {
        var row = new JSONObject().put("scripCode", 22377).put("tradingSymbol", "MAXHEALTH")
                .put("instType", "EQ").put("tickSize", 0.1);
        var entity = service.convertToEntity(row, "NC");
        assertThat(entity.getTickSize()).isEqualTo(0.1);
        assertThat(entity.getScripCode()).isEqualTo(22377);
        assertThat(entity.getExchange()).isEqualTo("NC");
    }

    @Test
    void preservesFineTicksAndLeavesMissingOrInvalidTicksUnknown() throws Throwable {
        assertThat(service.convertToEntity(new JSONObject().put("tickSize", "0.0025"), "MX").getTickSize())
                .isEqualTo(0.0025);
        for (Object value : new Object[]{JSONObject.NULL, "invalid", 0, -0.1}) {
            assertThat(service.convertToEntity(new JSONObject().put("tickSize", value), "NC").getTickSize()).isNull();
        }
        assertThat(service.convertToEntity(new JSONObject(), "NC").getTickSize()).isNull();
    }
    @Test
    void removesBcCpseNulPaddingWithoutDroppingTheInstrument() throws Throwable {
        var row = new JSONObject().put("scripCode", 1000203)
                .put("tradingSymbol", "CPSE\0\0").put("instType", "EQ")
                .put("tickSize", 0.0).put("expiry", JSONObject.NULL);
        var entity = service.convertToEntity(row, "BC");
        assertThat(entity.getTradingSymbol()).isEqualTo("CPSE");
        assertThat(entity.getScripCode()).isEqualTo(1000203);
        assertThat(entity.getTickSize()).isNull();
        assertThat(entity.getExpiry()).isNull();
    }

    @Test
    void sanitizesEveryPersistedTextFieldAndPreservesOtherUnicode() throws Throwable {
        var row = new JSONObject().put("tradingSymbol", "Société\0")
                .put("instType", "E\0Q").put("expiry", "29/09/2026\0")
                .put("optionType", "C\0E");
        var entity = service.convertToEntity(row, "B\0C");
        assertThat(entity.getTradingSymbol()).isEqualTo("Société");
        assertThat(entity.getExchange()).isEqualTo("BC");
        assertThat(entity.getInstrumentType()).isEqualTo("EQ");
        assertThat(entity.getExpiry()).isEqualTo("29/09/2026");
        assertThat(entity.getOptionType()).isEqualTo("CE");
    }
}
