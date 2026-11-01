package org.com.sharekhan.analysis;
import org.com.sharekhan.config.SecurityConfig;import org.com.sharekhan.service.AdminUserDetailsService;
import org.junit.jupiter.api.Test;import org.springframework.beans.factory.annotation.Autowired;import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;import org.springframework.boot.test.mock.mockito.MockBean;import org.springframework.context.annotation.Import;import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@WebMvcTest(HammerResearchController.class) @Import(SecurityConfig.class)
class HammerResearchSecurityTest {
 @Autowired MockMvc mvc;@MockBean HammerResearchService service;@MockBean AdminUserDetailsService users;
 @Test void authenticatedUsersCanReadNoStore()throws Exception {for(String role:new String[]{"USER","ADMIN"})mvc.perform(get("/api/stock-analysis/hammer").with(user("viewer").roles(role))).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));verify(service,times(2)).report();}
 @Test void anonymousAndOtherRolesDenied()throws Exception{mvc.perform(get("/api/stock-analysis/hammer")).andExpect(status().isUnauthorized());mvc.perform(get("/api/stock-analysis/hammer").with(user("viewer").roles("OTHER"))).andExpect(status().isForbidden());verifyNoInteractions(service);}
 @Test void noWriteRoute()throws Exception{mvc.perform(post("/api/stock-analysis/hammer").with(user("viewer").roles("USER")).with(csrf())).andExpect(status().isMethodNotAllowed());verifyNoInteractions(service);}
}
