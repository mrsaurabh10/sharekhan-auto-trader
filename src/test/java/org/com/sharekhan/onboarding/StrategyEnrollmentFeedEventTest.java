package org.com.sharekhan.onboarding;

import org.com.sharekhan.entity.*;
import org.com.sharekhan.repository.*;
import org.com.sharekhan.dto.StrategyApplyRequest;
import org.com.sharekhan.strategy.StrategyFeedChanged;
import org.com.sharekhan.strategy.nifty.NiftyThreeCandleRules;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.mockito.Mockito.*;

class StrategyEnrollmentFeedEventTest {
    @Test void enrollingNiftyPublishesFeedDemandAfterSavingItsSubscription(){
        var subscriptions=mock(StrategySubscriptionRepository.class);var users=mock(AppUserRepository.class);
        var events=mock(ApplicationEventPublisher.class);var enrollment=new StrategyEnrollmentService(subscriptions,users);
        ReflectionTestUtils.setField(enrollment,"events",events);
        when(users.lockForEnrollment(3L)).thenReturn(Optional.of(new AppUser()));
        when(subscriptions.findByStatusInAndTemplateIdIgnoreCaseAndSymbolIgnoreCaseAndAppUserId(anyList(),anyString(),anyString(),anyLong())).thenReturn(List.of());
        when(subscriptions.save(any())).thenAnswer(c->{StrategySubscriptionEntity row=c.getArgument(0);row.setId(188L);return row;});
        var request=new StrategyApplyRequest();request.setUserId(3L);request.setBrokerCredentialsId(4L);request.setLots(1);
        enrollment.enroll(request,NiftyThreeCandleRules.TEMPLATE,"NIFTY",NiftyThreeCandleRules.SOURCE);
        var order=inOrder(subscriptions,events);order.verify(subscriptions).findByStatusInAndTemplateIdIgnoreCaseAndSymbolIgnoreCaseAndAppUserId(anyList(),anyString(),anyString(),anyLong());
        order.verify(subscriptions).save(any());order.verify(events).publishEvent(new StrategyFeedChanged(NiftyThreeCandleRules.TEMPLATE));
    }
}
