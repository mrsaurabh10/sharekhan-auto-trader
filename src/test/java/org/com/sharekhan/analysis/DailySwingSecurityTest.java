package org.com.sharekhan.analysis;

import org.com.sharekhan.config.SecurityConfig;
import org.com.sharekhan.service.AdminUserDetailsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DailySwingController.class) @Import(SecurityConfig.class)
class DailySwingSecurityTest {
    @Autowired MockMvc mvc;@MockBean DailySwingService service;@MockBean AdminUserDetailsService users;
    @org.junit.jupiter.api.BeforeEach void clearStartupEvent(){clearInvocations(service);}
    @Test void bothRolesCanReadAndRequestAResearchScanWithCsrf()throws Exception{
        when(service.history()).thenReturn(List.of());when(service.requestScan()).thenReturn(true);
        for(String role:List.of("USER","ADMIN")){
            mvc.perform(get("/api/stock-analysis/swing/history").with(user("viewer").roles(role))).andExpect(status().isOk());
            mvc.perform(post("/api/stock-analysis/swing/scan").with(user("viewer").roles(role)).with(csrf())).andExpect(status().isAccepted());
        }verify(service,times(2)).requestScan();
    }
    @Test void anonymousAndUnsupportedRolesAreDeniedAndScanGetCannotMutate()throws Exception{
        mvc.perform(get("/api/stock-analysis/swing")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/stock-analysis/swing/history").with(user("other").roles("OTHER"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/stock-analysis/swing/scan").with(user("viewer").roles("USER"))).andExpect(status().isMethodNotAllowed());verifyNoInteractions(service);
    }
    @Test void postWithoutCsrfNeverStartsWork()throws Exception{
        mvc.perform(post("/api/stock-analysis/swing/scan").with(user("viewer").roles("USER"))).andExpect(status().isForbidden());verifyNoInteractions(service);
    }
}
