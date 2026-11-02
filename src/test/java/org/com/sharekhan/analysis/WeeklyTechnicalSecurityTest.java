package org.com.sharekhan.analysis;
import org.com.sharekhan.config.SecurityConfig;import org.com.sharekhan.service.AdminUserDetailsService;
import org.junit.jupiter.api.Test;import org.springframework.beans.factory.annotation.Autowired;import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;import org.springframework.boot.test.mock.mockito.MockBean;import org.springframework.context.annotation.Import;import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@WebMvcTest(WeeklyTechnicalController.class) @Import(SecurityConfig.class)
class WeeklyTechnicalSecurityTest {
 @Autowired MockMvc mvc;@MockBean WeeklyTechnicalService service;@MockBean SavedDailyAnalysis daily;@MockBean AdminUserDetailsService users;
 @Test void authenticatedUsersCanReadBothNoStoreRoutes()throws Exception{for(String role:new String[]{"USER","ADMIN"}){mvc.perform(get("/api/stock-analysis/weekly?symbol=RELIANCE").with(user("viewer").roles(role))).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));mvc.perform(get("/api/stock-analysis/weekly/symbols").with(user("viewer").roles(role))).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));}verify(service,times(2)).report("RELIANCE");}
 @Test void anonymousAndOtherRolesDenied()throws Exception{for(String p:new String[]{"/api/stock-analysis/weekly","/api/stock-analysis/weekly/symbols"}){mvc.perform(get(p)).andExpect(status().isUnauthorized());mvc.perform(get(p).with(user("viewer").roles("OTHER"))).andExpect(status().isForbidden());}verifyNoInteractions(service,daily);}
 @Test void noWriteRoute()throws Exception{mvc.perform(post("/api/stock-analysis/weekly").with(user("viewer").roles("USER")).with(csrf())).andExpect(status().isMethodNotAllowed());verifyNoInteractions(service);}
}
