package org.com.sharekhan.controller;
import org.com.sharekhan.strategy.mcx.NaturalGasLiveCandles;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.Map;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class NaturalGasDataDiagnosticsControllerTest {
    @Test void rejectsUnauthenticatedDiagnosticReadsAndReturnsOnlyCollectorSnapshot() throws Exception {
        var live=mock(NaturalGasLiveCandles.class);var controller=new NaturalGasDataDiagnosticsController(live,new org.com.sharekhan.strategy.mcx.NaturalGasDataPolicy(org.com.sharekhan.strategy.mcx.NaturalGasDataMode.ROLLING_WINDOW),mock(org.com.sharekhan.strategy.mcx.NaturalGasShoonyaCandles.class));
        ReflectionTestUtils.setField(controller,"adminToken","test-key");
        when(live.diagnostics(42)).thenReturn(Map.of("armed",true));
        var mvc=MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(get("/api/sharekhan/historical/mcx-capture").param("scripCode","42")).andExpect(status().isForbidden());
        mvc.perform(get("/api/sharekhan/historical/mcx-capture").header("X-Admin-Token","test-key").param("scripCode","42"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.armed").value(true))
            .andExpect(jsonPath("$.simulatorDataMode").value("ROLLING_WINDOW"))
            .andExpect(jsonPath("$.brokerDataMode").value("FULL_SESSION"));
        verify(live).diagnostics(42);
    }
}
