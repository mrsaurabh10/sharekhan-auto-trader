package org.com.sharekhan.controller;

import org.com.sharekhan.audit.AuditEventStore;
import org.com.sharekhan.config.SecurityConfig;
import org.com.sharekhan.entity.TradeAuditEventEntity;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.com.sharekhan.repository.TriggeredTradeSetupRepository;
import org.com.sharekhan.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest({TradeExecutionController.class, TradeAuditController.class})
@Import(SecurityConfig.class)
class PersonalTradeActionsSecurityTest {
    @Autowired MockMvc mvc;
    @MockBean TradeExecutionService execution;
    @MockBean TriggeredTradeSetupRepository trades;
    @MockBean CurrentUserService current;
    @MockBean PriceTriggerService prices;
    @MockBean AtrPreviousDayTradeLevelService levels;
    @MockBean AuditEventStore audit;
    @MockBean AdminUserDetailsService users;

    TriggeredTradeSetupEntity trade;

    @BeforeEach void setup() {
        trade = TriggeredTradeSetupEntity.builder().id(101L).appUserId(4L).status(TriggeredTradeStatus.EXECUTED).build();
        when(current.currentAppUserIdOrNull()).thenReturn(4L);
        when(trades.findById(101L)).thenReturn(Optional.of(trade));
        when(trades.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(execution.moveStopLossToCost(101L, TradeExecutionService.CostStopReference.PREMIUM))
                .thenReturn(TradeExecutionService.MoveStopLossToCostResult.UPDATED_PREMIUM_BREAK_EVEN);
        when(execution.modifyExitOrderPrice(101L, 110d, "MANUAL_MODIFY"))
                .thenReturn(new TradeExecutionService.ModifyExitOrderResult(true, "Submitted"));
        when(execution.resetNonIntradayExitOrderIfInactive(101L))
                .thenReturn(TradeExecutionService.EndOfDayExitResetResult.RESET);
    }

    private List<MockHttpServletRequestBuilder> mutations() {
        return List.of(
                put("/api/trades/execution/101").contentType("application/json").content("{\"stopLoss\":95,\"target1\":125}"),
                post("/api/trades/move-sl-to-cost/101?reference=PREMIUM"),
                post("/api/trades/exit-order/101/modify").contentType("application/json").content("{\"price\":110}"),
                post("/api/trades/execution/101/reset-to-executed"),
                post("/api/trades/square-off/101"));
    }

    @Test void regularUserCanOperateOnOwnTrade() throws Exception {
        for (var request : mutations()) mvc.perform(request.with(user("sample").roles("USER")).with(csrf())).andExpect(status().isOk());
        verify(trades).save(trade);
        verify(execution).moveStopLossToCost(101L, TradeExecutionService.CostStopReference.PREMIUM);
        verify(execution).modifyExitOrderPrice(101L, 110d, "MANUAL_MODIFY");
        verify(execution).resetNonIntradayExitOrderIfInactive(101L);
        verify(execution).squareOffTrade(101L, null, TriggeredTradeStatus.EXIT_ORDER_PLACED);
    }

    @Test void allMutationsRejectAnotherUsersTrade() throws Exception {
        trade.setAppUserId(5L);
        for (var request : mutations()) mvc.perform(request.with(user("sample").roles("USER")).with(csrf())).andExpect(status().isForbidden());
        verify(trades, never()).save(any());
        verifyNoInteractions(execution);
    }

    @Test void allMutationsRequireCsrfAndAuthentication() throws Exception {
        for (var request : mutations()) mvc.perform(request.with(user("sample").roles("USER"))).andExpect(status().isForbidden());
        for (var request : mutations()) mvc.perform(request.with(csrf())).andExpect(status().isUnauthorized());
        verifyNoInteractions(execution);
        verify(trades, never()).save(any());
    }

    @Test void missingOwnerIsNotTreatedAsCurrentUser() throws Exception {
        trade.setAppUserId(null);
        for (var request : mutations()) mvc.perform(request.with(user("sample").roles("USER")).with(csrf())).andExpect(status().isForbidden());
        verifyNoInteractions(execution);
    }

    @Test void auditShowsOwnEventsAndRejectsOtherUsersEventsForBothFilters() throws Exception {
        var own = TradeAuditEventEntity.builder().appUserId(4L).eventType("STOP_UPDATED").build();
        var other = TradeAuditEventEntity.builder().appUserId(5L).eventType("STOP_UPDATED").build();
        when(audit.findByTradeId(101L)).thenReturn(List.of(own));
        when(audit.findByTriggerRequestId(201L)).thenReturn(List.of(own));
        for (String query : List.of("tradeId=101", "triggerRequestId=201"))
            mvc.perform(get("/api/trades/audit?" + query).with(user("sample").roles("USER")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$[0].eventType").value("STOP_UPDATED"));
        when(audit.findByTradeId(101L)).thenReturn(List.of(other));
        when(audit.findByTriggerRequestId(201L)).thenReturn(List.of(own, other));
        for (String query : List.of("tradeId=101", "triggerRequestId=201"))
            mvc.perform(get("/api/trades/audit?" + query).with(user("sample").roles("USER"))).andExpect(status().isForbidden());
    }

    @Test void brokerRefusalToResetIsReturnedAsConflict() throws Exception {
        when(execution.resetNonIntradayExitOrderIfInactive(101L))
                .thenReturn(TradeExecutionService.EndOfDayExitResetResult.SKIPPED_BROKER_UNAVAILABLE);
        mvc.perform(post("/api/trades/execution/101/reset-to-executed").with(user("sample").roles("USER")).with(csrf()))
                .andExpect(status().isConflict()).andExpect(content().string(org.hamcrest.Matchers.containsString("was not reset")));
    }
}
