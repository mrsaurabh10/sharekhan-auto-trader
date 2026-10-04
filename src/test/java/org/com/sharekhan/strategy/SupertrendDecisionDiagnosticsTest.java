package org.com.sharekhan.strategy;

import org.com.sharekhan.dto.StrategyApplyRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SupertrendDecisionDiagnosticsTest {
    @Test void countsOncePerCandleAndKeepsBrokerCountersSeparate() {
        StrategySupport support=mock(StrategySupport.class);
        var diagnostics=new SupertrendDecisionDiagnostics(support);
        var request=new StrategyApplyRequest(); request.setUserId(1L); request.setBrokerCredentialsId(2L);
        var metadata=new StrategyMetadata("ST_RSI_EMA_ADX_CE","CE","test","CE");
        var rejected=new SupertrendSignalRules.Result(List.of(new SupertrendSignalRules.Failure("RSI_RANGE","RSI out of range")));
        var indicator=SupertrendSignalRulesTest.snapshot(true,80,80,21);
        diagnostics.record(request,metadata,"NIFTY",indicator,rejected);
        diagnostics.record(request,metadata,"NIFTY",indicator,rejected);
        var c=indicator.candle();
        var next=new IndicatorSnapshot(new StrategyCandle(c.date(),c.time().plusMinutes(5),c.open(),c.high(),c.low(),c.close(),c.volume()),95,60,60,96,21,30,15);
        diagnostics.record(request,metadata,"NIFTY",next,new SupertrendSignalRules.Result(List.of()));
        request.setBrokerCredentialsId(3L);
        diagnostics.record(request,metadata,"NIFTY",indicator,rejected);
        var reasons=ArgumentCaptor.forClass(String.class);
        verify(support,times(3)).auditStrategy(eq(request),eq(metadata),eq("NIFTY"),eq("STRATEGY_EVALUATION"),anyString(),reasons.capture(),isNull(),isNull());
        assertThat(reasons.getAllValues().get(0)).contains("evaluated=1, rejected=1", "RSI_RANGE=1");
        assertThat(reasons.getAllValues().get(1)).contains("evaluated=2, rejected=1", "RSI_RANGE=1");
        assertThat(reasons.getAllValues().get(2)).contains("evaluated=1, rejected=1");
    }
}
