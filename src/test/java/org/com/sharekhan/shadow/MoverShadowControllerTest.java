package org.com.sharekhan.shadow;
import org.junit.jupiter.api.Test;
import org.com.sharekhan.service.CurrentUserService;
import java.time.LocalDate;
import java.util.Map;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class MoverShadowControllerTest {
    @Test void ownershipIsCheckedBeforeAnyReportRead(){
        var users=mock(CurrentUserService.class);var reports=mock(MoverShadowReport.class);var c=new MoverShadowController(reports,users);
        when(users.currentAppUserIdOrNull()).thenReturn(null);
        assertThat(c.report(null,3L).getStatusCode().value()).isEqualTo(401);
        when(users.currentAppUserIdOrNull()).thenReturn(3L);
        assertThat(c.report(null,4L).getStatusCode().value()).isEqualTo(403);verifyNoInteractions(reports);
        when(reports.report(any(),eq(3L))).thenReturn(Map.of("userId",3));assertThat(c.report(null,null).getStatusCode().value()).isEqualTo(200);
        when(users.isAdmin()).thenReturn(true);assertThat(c.report(null,4L).getStatusCode().value()).isEqualTo(200);
    }
}
