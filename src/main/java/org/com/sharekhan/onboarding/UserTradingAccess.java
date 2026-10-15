package org.com.sharekhan.onboarding;

import lombok.RequiredArgsConstructor;
import org.com.sharekhan.entity.BrokerCredentialsEntity;
import org.com.sharekhan.repository.BrokerCredentialsRepository;
import org.com.sharekhan.service.CurrentUserService;
import org.com.sharekhan.dto.TriggerRequest;
import org.com.sharekhan.dto.StrategyApplyRequest;
import org.springframework.stereotype.Service;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import static org.springframework.http.HttpStatus.BAD_REQUEST;

@Service @RequiredArgsConstructor
public class UserTradingAccess {
    private final CurrentUserService current;
    private final BrokerCredentialsRepository brokers;
    private final BrokerConnectionRepository connections;
    private final AccountProfileRepository profiles;
    public BrokerCredentialsEntity owned(Long id) {
        Long userId = current.requireAppUserId();
        if (id == null) throw new ResponseStatusException(BAD_REQUEST, "Choose a broker account");
        return brokers.findById(id).filter(b -> userId.equals(b.getAppUserId()))
            .orElseThrow(() -> new AccessDeniedException("Broker account does not belong to you"));
    }
    public boolean connected(Long id) {
        return connections.findById(id).map(c -> c.getVerifiedUntil() != null
            && c.getVerifiedUntil().isAfter(Instant.now())).orElse(false);
    }
    public void requireReady(Long userId, Long brokerId) {
        BrokerCredentialsEntity b = brokers.findById(brokerId == null ? -1L : brokerId)
            .filter(c -> userId != null && userId.equals(c.getAppUserId())).orElseThrow(() -> new AccessDeniedException("Choose your own broker account"));
        if (b.getCustomerId() == null || b.getCustomerId() <= 0 || !Boolean.TRUE.equals(b.getActive()) || !Boolean.TRUE.equals(b.getTradingEnabled())
            || !"Sharekhan".equalsIgnoreCase(b.getBrokerName()) || !connected(b.getId()))
            throw new ResponseStatusException(BAD_REQUEST, "Connect your Sharekhan account and enable trading first");
    }
    public void requireOnboardedReady(Long userId, Long brokerId) {
        if (userId != null && (profiles.existsById(userId) || (brokerId != null && connections.existsById(brokerId)))) requireReady(userId, brokerId);
    }
    public void scope(TriggerRequest request) {
        if (current.isAdmin()) return;
        Long userId = current.requireAppUserId();
        requireReady(userId, request.getBrokerCredentialsId());
        request.setUserId(userId); request.setTargetBrokerCredentialsIds(null);
        request.setRoutingMode(null); request.setBrokerName(null); request.setSource("user-ui");
        if (request.getQuantity() == null || request.getQuantity() < 1 || request.getQuantity() > 1000)
            throw new ResponseStatusException(BAD_REQUEST, "Quantity must be between 1 and 1000 lots");
        if (request.getInstrument() == null || request.getInstrument().isBlank() || request.getExchange() == null)
            throw new ResponseStatusException(BAD_REQUEST, "Select an instrument and exchange");
        String exchange = request.getExchange().trim().toUpperCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("NC", "BC", "NF", "BF").contains(exchange))
            throw new ResponseStatusException(BAD_REQUEST, "Select a supported exchange");
        request.setExchange(exchange);
        request.setInstrument(request.getInstrument().trim().toUpperCase(java.util.Locale.ROOT));
        if (request.getAction() != null && !"BUY".equalsIgnoreCase(request.getAction()))
            throw new ResponseStatusException(BAD_REQUEST, "This order form supports buy entries only");
        if (java.util.Set.of("NF", "BF").contains(exchange)) {
            if (!java.util.Set.of("CE", "PE").contains(request.getOptionType() == null ? "" : request.getOptionType())
                    || !positive(request.getStrikePrice()) || request.getExpiry() == null)
                throw new ResponseStatusException(BAD_REQUEST, "Choose option type, strike and expiry");
            try {
                java.time.LocalDate.parse(request.getExpiry(), java.time.format.DateTimeFormatter.ofPattern("dd/MM/uuuu")
                        .withResolverStyle(java.time.format.ResolverStyle.STRICT));
            } catch (java.time.format.DateTimeParseException ex) {
                throw new ResponseStatusException(BAD_REQUEST, "Expiry must be a valid date in dd/MM/yyyy format");
            }
        } else { request.setOptionType(null); request.setStrikePrice(null); request.setExpiry(null); }
        request.setLots(null); // Quantity is the sole user-facing sizing input.
        request.setQuickTrade(false);
        request.setBrokerProductType(null);
        request.setUseSpotPrice(false); request.setUseSpotForEntry(false);
        request.setUseSpotForSl(false); request.setUseSpotForTarget(false); request.setSpotScripCode(null);
        request.setAction("BUY");
        if (!positive(request.getEntryPrice()) || !positive(request.getStopLoss()))
            throw new ResponseStatusException(BAD_REQUEST, "Entry and stop-loss must be positive prices");
        if (request.getStopLoss() >= request.getEntryPrice()) throw new ResponseStatusException(BAD_REQUEST, "For a buy order, stop-loss must be below entry");
        for (Double target : new Double[]{request.getTarget1(), request.getTarget2(), request.getTarget3()})
            if (target != null && (!positive(target) || target <= request.getEntryPrice())) throw new ResponseStatusException(BAD_REQUEST, "For a buy order, targets must be above entry");
    }
    private boolean positive(Double number) { return number != null && Double.isFinite(number) && number > 0; }
    public void scope(StrategyApplyRequest request) {
        if (current.isAdmin()) return;
        request.setUserId(current.requireAppUserId());
        requireReady(request.getUserId(), request.getBrokerCredentialsId());
        request.setSource("strategy:" + request.getTemplateId());
        if (request.getLots() == null || request.getLots() < 1 || request.getLots() > 1000)
            throw new ResponseStatusException(BAD_REQUEST, "Choose between 1 and 1000 lots");
    }
}
