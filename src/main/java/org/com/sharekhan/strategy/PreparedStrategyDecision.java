package org.com.sharekhan.strategy;
import org.com.sharekhan.dto.*;
import org.springframework.beans.BeanUtils;

/** The shared template is never returned to a caller or mutated by account binding. */
public final class PreparedStrategyDecision {
    private final StrategyApplyResponse template;
    public PreparedStrategyDecision(StrategyApplyResponse template) {
        if (template.getTradeRequest() != null) throw new IllegalArgumentException("A shared decision cannot contain an account order");
        this.template = copy(template);
        var trigger = this.template.getTriggerRequest();
        if (trigger != null) {
            trigger.setUserId(null); trigger.setBrokerCredentialsId(null); trigger.setStrategyRunId(null);
            trigger.setQuantity(null); trigger.setLots(null);
        }
    }
    public StrategyApplyResponse forSubscriber(StrategyApplyRequest request) {
        var result = copy(template); var trigger = result.getTriggerRequest();
        if (trigger != null) {
            trigger.setUserId(request.getUserId()); trigger.setBrokerCredentialsId(request.getBrokerCredentialsId());
            trigger.setLots(request.getLots()); trigger.setQuantity(request.getLots());
            if (request.getSource() != null && !request.getSource().isBlank()) trigger.setSource(request.getSource().trim());
            if (request.getIntraday() != null) trigger.setIntraday(request.getIntraday());
        }
        return result;
    }
    private static StrategyApplyResponse copy(StrategyApplyResponse original) {
        var response = StrategyApplyResponse.builder().build(); BeanUtils.copyProperties(original, response);
        if (original.getTriggerRequest() != null) {
            var trigger = new TriggerRequest(); BeanUtils.copyProperties(original.getTriggerRequest(), trigger);
            response.setTriggerRequest(trigger);
        }
        return response;
    }
}
