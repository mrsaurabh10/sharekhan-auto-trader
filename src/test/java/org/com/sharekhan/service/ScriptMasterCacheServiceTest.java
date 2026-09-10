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
}
