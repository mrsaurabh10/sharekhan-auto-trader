package org.com.sharekhan.controller;
import org.com.sharekhan.service.CsvBacktestService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;
import java.time.LocalTime;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class CsvBacktestControllerTest {
    @Test void unauthorizedUploadsCannotReachStorage() throws Exception {
        var service=mock(CsvBacktestService.class);var controller=new CsvBacktestController(service);
        ReflectionTestUtils.setField(controller,"adminToken","test-secret");
        var file=new MockMultipartFile("file","data.csv","text/csv","date,open,high,low,close,volume".getBytes());
        MockMvcBuilders.standaloneSetup(controller).build().perform(multipart("/api/backtests/csv/datasets").file(file).param("symbol","NIFTY"))
                .andExpect(status().isForbidden());verifyNoInteractions(service);
    }
    @Test void runUsesExplicitExecutionDefaults() throws Exception {
        var service=mock(CsvBacktestService.class);var controller=new CsvBacktestController(service);
        ReflectionTestUtils.setField(controller,"adminToken","test-secret");
        MockMvcBuilders.standaloneSetup(controller).build().perform(post("/api/backtests/csv/datasets/dataset-id/run")
                .header("X-Admin-Token","test-secret").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        verify(service).run("dataset-id",null,null,2,0,0,LocalTime.of(15,20));
    }
    @Test void straddleEndpointRequiresAdminTokenAndForwardsExplicitExpiryDates() throws Exception {
        var service=mock(CsvBacktestService.class);var controller=new CsvBacktestController(service);
        ReflectionTestUtils.setField(controller,"adminToken","test-secret");
        var mvc=MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(post("/api/backtests/csv/datasets/id/expiry-straddle").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());verifyNoInteractions(service);
        mvc.perform(post("/api/backtests/csv/datasets/id/expiry-straddle").header("X-Admin-Token","test-secret")
                .contentType(MediaType.APPLICATION_JSON).content("{\"lots\":2,\"expiryDates\":[\"2026-10-13\"]}"))
                .andExpect(status().isOk());
        verify(service).runExpiryStraddle("id",null,null,2,java.util.List.of(java.time.LocalDate.of(2026,10,13)));
    }
}
