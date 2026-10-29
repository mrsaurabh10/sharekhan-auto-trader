package org.com.sharekhan.analysis;

import org.com.sharekhan.config.SecurityConfig;
import org.com.sharekhan.service.AdminUserDetailsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(StockAnalysisController.class)
@Import(SecurityConfig.class)
class StockAnalysisSecurityTest {
    @Autowired MockMvc mvc;
    @MockBean StockAnalysisService service;
    @MockBean AdminUserDetailsService users;
    @Test void everySignedInRoleCanLoadPageAndResearchWithoutTradingActivation() throws Exception {
        when(service.search("BC","DEMO")).thenReturn(List.of(new StockAnalysisService.Instrument(22,"DEMO","BC")));
        when(service.analyze("BC",22,"INTRADAY")).thenReturn(StockAnalysisService.calculate(
                new StockAnalysisService.Instrument(22,"DEMO","BC"),"INTRADAY",List.of(),java.time.Instant.parse("2026-10-29T12:00:00Z")));
        for (String role : new String[]{"USER","ADMIN"}) {
            mvc.perform(get("/stock-analysis").with(user("viewer").roles(role))).andExpect(status().isOk()).andExpect(view().name("stock-analysis"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Indian Stock Analysis")));
            mvc.perform(get("/api/stock-analysis/instruments").param("exchange","BC").param("q","DEMO").with(user("viewer").roles(role)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].exchange").value("BC"));
            mvc.perform(get("/api/stock-analysis/report").param("exchange","BC").param("scripCode","22").with(user("viewer").roles(role)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.provider").value("Sharekhan"))
                .andExpect(jsonPath("$.dataStatus").value("UNAVAILABLE")).andExpect(jsonPath("$.candles").isEmpty());
        }
        verify(service,times(2)).analyze("BC",22,"INTRADAY");
    }
    @Test void anonymousApiAccessIsDeniedAndPageRequiresSignIn() throws Exception {
        mvc.perform(get("/stock-analysis").accept("text/html")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrlPattern("**/login"));
        mvc.perform(get("/api/stock-analysis/instruments")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/stock-analysis/report").param("exchange","NC").param("scripCode","10")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
    @Test void unsupportedRolesCannotReadAndPageAssetsArePublic() throws Exception {
        mvc.perform(get("/api/stock-analysis/instruments").with(user("other").roles("OTHER"))).andExpect(status().isForbidden());
        mvc.perform(get("/portal/stock-analysis.js")).andExpect(status().isOk()); verifyNoInteractions(service);
    }
    @Test void malformedReportRequestDoesNotCallProviderService() throws Exception {
        mvc.perform(get("/api/stock-analysis/report").param("exchange","NC").with(user("viewer").roles("USER")))
            .andExpect(status().isBadRequest()); verifyNoInteractions(service);
    }
}
