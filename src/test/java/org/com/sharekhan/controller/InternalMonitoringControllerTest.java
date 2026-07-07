package org.com.sharekhan.controller;

import org.com.sharekhan.dto.monitoring.MonitoringSnapshotResponse;
import org.com.sharekhan.service.MonitoringSnapshotService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InternalMonitoringControllerTest {

    @Test
    void returnsSnapshotForValidToken() throws Exception {
        MonitoringSnapshotService service = mock(MonitoringSnapshotService.class);
        InternalMonitoringController controller = controller(service, "monitor-secret");
        when(service.snapshot()).thenReturn(new MonitoringSnapshotResponse(
                LocalDateTime.of(2026, 7, 7, 10, 30), "Asia/Kolkata", List.of(), List.of()));

        mockMvc(controller).perform(get("/internal/monitoring/snapshot")
                        .header("X-Monitoring-Token", "monitor-secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timezone").value("Asia/Kolkata"))
                .andExpect(jsonPath("$.activeTrades").isArray())
                .andExpect(jsonPath("$.closedToday").isArray());

        verify(service).snapshot();
    }

    @Test
    void rejectsInvalidToken() throws Exception {
        MonitoringSnapshotService service = mock(MonitoringSnapshotService.class);
        InternalMonitoringController controller = controller(service, "monitor-secret");

        mockMvc(controller).perform(get("/internal/monitoring/snapshot")
                        .header("X-Monitoring-Token", "wrong"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));

        verifyNoInteractions(service);
    }

    @Test
    void refusesToExposeSnapshotWhenTokenIsNotConfigured() throws Exception {
        MonitoringSnapshotService service = mock(MonitoringSnapshotService.class);
        InternalMonitoringController controller = controller(service, "");

        mockMvc(controller).perform(get("/internal/monitoring/snapshot"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("monitoring_not_configured"));

        verifyNoInteractions(service);
    }

    private InternalMonitoringController controller(MonitoringSnapshotService service, String token) {
        InternalMonitoringController controller = new InternalMonitoringController(service);
        ReflectionTestUtils.setField(controller, "configuredToken", token);
        return controller;
    }

    private MockMvc mockMvc(InternalMonitoringController controller) {
        return MockMvcBuilders.standaloneSetup(controller).build();
    }
}
