package org.com.sharekhan.controller;

import org.com.sharekhan.config.SecurityConfig;
import org.com.sharekhan.service.*;
import org.com.sharekhan.onboarding.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(StrategyController.class)
@Import({SecurityConfig.class, PortalExceptionHandler.class})
class StrategyPauseResumeSecurityTest {
    @Autowired MockMvc mvc;
    @MockBean StrategyTemplateService templates;
    @MockBean StrategySubscriptionService subscriptions;
    @MockBean StrategySubscriptionLifecycleService lifecycle;
    @MockBean CurrentUserService current;
    @MockBean UserTradingAccess access;
    @MockBean AdminUserDetailsService users;

    @Test void requiresAuthenticationAndCsrf() throws Exception {
        for (String action : new String[]{"pause", "resume"}) {
            String url="/api/strategies/subscriptions/9/"+action;
            mvc.perform(post(url).with(csrf())).andExpect(status().isUnauthorized());
            mvc.perform(post(url).with(user("owner").roles("USER"))).andExpect(status().isForbidden());
        }
        verifyNoInteractions(lifecycle);
    }
    @Test void userIdentityComesFromSessionAndAdminScopeIsExplicit() throws Exception {
        when(current.currentAppUserIdOrNull()).thenReturn(1L);
        mvc.perform(post("/api/strategies/subscriptions/9/pause?userId=99").with(user("owner").roles("USER")).with(csrf()))
            .andExpect(status().isOk());
        verify(lifecycle).pause(9L,1L,false);
        when(current.isAdmin()).thenReturn(true);
        mvc.perform(post("/api/strategies/subscriptions/9/resume").with(user("admin").roles("ADMIN")).with(csrf()))
            .andExpect(status().isOk());
        verify(lifecycle).resume(9L,1L,true);
    }
    @Test void foreignOwnerAndInvalidStateHaveUsefulHttpResponses() throws Exception {
        when(lifecycle.pause(anyLong(),any(),anyBoolean())).thenThrow(new org.springframework.security.access.AccessDeniedException("Forbidden"));
        mvc.perform(post("/api/strategies/subscriptions/9/pause").with(user("foreign").roles("USER")).with(csrf()))
            .andExpect(status().isForbidden());
        when(lifecycle.resume(anyLong(),any(),anyBoolean())).thenThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"Only paused strategies can resume"));
        mvc.perform(post("/api/strategies/subscriptions/9/resume").with(user("owner").roles("USER")).with(csrf()))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("Only paused strategies can resume"));
    }
}
