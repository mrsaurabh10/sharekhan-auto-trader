package org.com.sharekhan.onboarding;

import org.com.sharekhan.config.SecurityConfig;
import org.com.sharekhan.controller.LoginController;
import org.com.sharekhan.controller.AuthController;
import org.com.sharekhan.auth.AccessTokenPersistenceService;
import org.com.sharekhan.service.BrokerCredentialsService;
import org.com.sharekhan.service.AdminUserDetailsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.*;

@WebMvcTest(controllers={AccountController.class,PortalController.class,LoginController.class,AuthController.class})
@Import({SecurityConfig.class,PortalSecurityTest.Probes.class,PortalExceptionHandler.class})
class PortalSecurityTest {
    @Autowired MockMvc mvc;
    @MockBean AccountService accounts;
    @MockBean AdminUserDetailsService users;
    @MockBean BrokerCredentialsService credentials;
    @MockBean AccessTokenPersistenceService tokens;
    @TestConfiguration static class Probes {
        @Bean ProbeController probeController() { return new ProbeController(); }
    }
    @RestController static class ProbeController {
        @GetMapping("/api/strategies/templates") String strategy(){return "[]";}
        @GetMapping("/api/orders/requests") String orders(){return "[]";}
        @GetMapping("/debug/test") String debug(){return "admin only";}
    }
    @Test void signupLoginAndStaticAssetsArePublicButStrategiesRequireLogin() throws Exception {
        mvc.perform(get("/account")).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Create your account")));
        mvc.perform(get("/login")).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Sign in to your workspace")));
        mvc.perform(get("/portal/portal.css")).andExpect(status().isOk());
        mvc.perform(get("/api/strategies/templates")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/orders/requests")).andExpect(status().isUnauthorized());
    }
    @Test void registrationRequiresCsrf() throws Exception {
        mvc.perform(post("/api/account/register").contentType("application/json").content("{}"))
            .andExpect(status().isForbidden());
        verifyNoInteractions(accounts);
    }
    @Test void registrationWithCsrfUsesServiceAndGenericResponse() throws Exception {
        mvc.perform(post("/api/account/register").with(csrf()).contentType("application/json")
            .content("{\"displayName\":\"Alex\",\"email\":\"alex@example.test\",\"password\":\"long password here\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.message").exists());
        verify(accounts).register(any());
    }
    @Test void nonAdminCannotUseUnknownOrCredentialAdministrationRoutes() throws Exception {
        mvc.perform(get("/debug/test").with(user("alex").roles("USER"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/credentials").with(user("alex").roles("USER")).with(csrf())
            .contentType("application/json").content("{\"brokerName\":\"Sharekhan\"}"))
            .andExpect(status().isForbidden());
        verifyNoInteractions(credentials);
    }
    @Test void missingAdminTokenFailsClosedEvenForAdminSession() throws Exception {
        mvc.perform(post("/api/auth/credentials").with(user("admin").roles("ADMIN")).with(csrf())
            .contentType("application/json").content("{\"brokerName\":\"Sharekhan\"}"))
            .andExpect(status().isForbidden());
        verifyNoInteractions(credentials);
    }
    @Test void userCanLoadPersonalPortalButNotLegacyAdminPage() throws Exception {
        mvc.perform(get("/dashboard").with(user("alex").roles("USER"))).andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Create an order")));
        mvc.perform(get("/admin-dashboard.html").with(user("alex").roles("USER"))).andExpect(status().isForbidden());
    }
    @Test void workspaceLinkAndDhanCallbackRenderPersonalPortalForBothRoles() throws Exception {
        for (String role : new String[]{"ADMIN", "USER"}) {
            for (String path : new String[]{"/portal", "/portal/", "/dashboard"}) {
                mvc.perform(get(path).param("dhan", "connected").with(user("alex").roles(role)))
                    .andExpect(status().isOk()).andExpect(view().name("portal"))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("Create an order")));
            }
        }
    }
    @Test void workspaceRequiresLoginWhileItsAssetsRemainPublic() throws Exception {
        for (String path : new String[]{"/portal", "/portal/", "/dashboard"}) {
            mvc.perform(get(path).accept("text/html")).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
        }
        mvc.perform(get("/portal/portal.js")).andExpect(status().isOk());
    }
    @Test void rootSelectsTheCorrectWorkspaceForTheRole() throws Exception {
        mvc.perform(get("/").with(user("admin").roles("ADMIN")))
            .andExpect(redirectedUrl("/admin/dashboard"));
        mvc.perform(get("/").with(user("alex").roles("USER")))
            .andExpect(redirectedUrl("/dashboard"));
    }
    @Test void logoutRequiresPostAndCsrf() throws Exception {
        mvc.perform(post("/logout").with(user("alex").roles("USER"))).andExpect(status().isForbidden());
        mvc.perform(post("/logout").accept("text/html").with(user("alex").roles("USER")).with(csrf())).andExpect(status().is3xxRedirection());
    }
}
