package org.com.sharekhan.telegram;

import org.com.sharekhan.config.SecurityConfig;
import org.com.sharekhan.onboarding.PortalExceptionHandler;
import org.com.sharekhan.service.AdminUserDetailsService;
import org.com.sharekhan.service.CurrentUserService;
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

@WebMvcTest(TelegramSettingsController.class)
@Import({SecurityConfig.class,PortalExceptionHandler.class})
class TelegramSettingsSecurityTest {
    @Autowired MockMvc mvc;
    @MockBean TelegramSettingsService settings;
    @MockBean CurrentUserService current;
    @MockBean AdminUserDetailsService users;
    @Test void anonymousRequestsRequireLoginAndMutationsRequireCsrf() throws Exception {
        mvc.perform(get("/api/user/telegram")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/user/telegram").with(user("vaibhav").roles("USER"))
            .contentType("application/json").content("{\"username\":\"vaibhavhubli\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/user/telegram/link").with(user("vaibhav").roles("USER"))).andExpect(status().isForbidden());
        mvc.perform(delete("/api/user/telegram").with(user("vaibhav").roles("USER"))).andExpect(status().isForbidden());
        verifyNoInteractions(settings);
    }
    @Test void requestedUserIdCannotOverrideSignedInAccount() throws Exception {
        when(current.requireAppUserId()).thenReturn(4L);
        when(settings.get(4L)).thenReturn(new TelegramSettingsService.Status("vaibhavhubli",null,false,true,"test_bot"));
        mvc.perform(get("/api/user/telegram").param("userId","1").with(user("vaibhav").roles("USER")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.username").value("vaibhavhubli"));
        mvc.perform(put("/api/user/telegram").with(user("vaibhav").roles("USER")).with(csrf())
            .contentType("application/json").content("{\"username\":\"vaibhavhubli\",\"userId\":1,\"chatId\":\"999\"}"))
            .andExpect(status().isOk());
        verify(settings).save(eq(4L),eq(new TelegramSettingsService.Request("vaibhavhubli")));
        mvc.perform(post("/api/user/telegram/link").with(user("vaibhav").roles("USER")).with(csrf())).andExpect(status().isOk());
        mvc.perform(delete("/api/user/telegram").with(user("vaibhav").roles("USER")).with(csrf())).andExpect(status().isOk());
        verify(settings).link(4L); verify(settings).remove(4L); verify(settings,never()).get(1L);
    }
    @Test void noPersonalAppUserFailsClosed() throws Exception {
        when(current.requireAppUserId()).thenThrow(new org.springframework.security.access.AccessDeniedException("App user login required"));
        mvc.perform(get("/api/user/telegram").with(user("admin").roles("ADMIN"))).andExpect(status().isForbidden());
        verifyNoInteractions(settings);
    }
}
