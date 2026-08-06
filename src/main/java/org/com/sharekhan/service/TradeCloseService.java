package org.com.sharekhan.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.cache.LtpCacheService;
import org.com.sharekhan.dto.CloseTradesRequest;
import org.com.sharekhan.dto.CloseTradesResponse;
import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.entity.TriggerTradeRequestEntity;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.com.sharekhan.repository.TriggerTradeRequestRepository;
import org.com.sharekhan.repository.TriggeredTradeSetupRepository;
import org.com.sharekhan.ws.WebSocketSubscriptionHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class TradeCloseService {
    private static final List<DateTimeFormatter> EXPIRY_FORMATS = List.of(
            DateTimeFormatter.ofPattern("dd/MM/uuuu"),
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("dd-MMM-uuuu", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd MMM uuuu", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd MMMM uuuu", Locale.ENGLISH)
    );

    private static final Map<String, Integer> MONTHS = Map.ofEntries(
            Map.entry("JAN", 1), Map.entry("JANUARY", 1),
            Map.entry("FEB", 2), Map.entry("FEBRUARY", 2),
            Map.entry("MAR", 3), Map.entry("MARCH", 3),
            Map.entry("APR", 4), Map.entry("APRIL", 4),
            Map.entry("MAY", 5),
            Map.entry("JUN", 6), Map.entry("JUNE", 6),
            Map.entry("JUL", 7), Map.entry("JULY", 7),
            Map.entry("AUG", 8), Map.entry("AUGUST", 8),
            Map.entry("SEP", 9), Map.entry("SEPT", 9), Map.entry("SEPTEMBER", 9),
            Map.entry("OCT", 10), Map.entry("OCTOBER", 10),
            Map.entry("NOV", 11), Map.entry("NOVEMBER", 11),
            Map.entry("DEC", 12), Map.entry("DECEMBER", 12)
    );

    private static final List<TriggeredTradeStatus> OPEN_REQUEST_STATUSES = List.of(
            TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION,
            TriggeredTradeStatus.TRIGGERED
    );

    private static final List<TriggeredTradeStatus> OPEN_EXECUTION_STATUSES = List.of(
            TriggeredTradeStatus.EXECUTED,
            TriggeredTradeStatus.TARGET_ORDER_PLACED
    );

    private final TriggerTradeRequestRepository triggerTradeRequestRepository;
    private final TriggeredTradeSetupRepository triggeredTradeSetupRepository;
    private final TradeExecutionService tradeExecutionService;
    private final LtpCacheService ltpCacheService;
    private final WebSocketSubscriptionHelper webSocketSubscriptionHelper;

    @Autowired(required = false)
    private ShoonyaQuoteService shoonyaQuoteService;
    @Autowired(required = false)
    private org.com.sharekhan.repository.ScriptMasterRepository scriptMasterRepository;

    public CloseTradesResponse closeAllByContract(CloseTradesRequest closeRequest) {
        String rawInstrument = closeRequest != null && closeRequest.getInstrument() != null
                ? closeRequest.getInstrument()
                : closeRequest != null ? closeRequest.getSymbol() : null;
        Double price = closeRequest != null ? closeRequest.getPrice() : null;
        String reason = closeRequest != null ? closeRequest.getReason() : null;
        String normalizedInstrument = normalizeInstrument(rawInstrument);
        if (normalizedInstrument == null) {
            throw new IllegalArgumentException("instrument is required");
        }
        String normalizedOptionType = normalizeOption(closeRequest != null ? closeRequest.getOptionType() : null);
        Double strikePrice = closeRequest != null ? closeRequest.getStrikePrice() : null;
        LocalDate expiry = parseExpiry(closeRequest != null ? closeRequest.getExpiry() : null);

        if (normalizedOptionType == null) {
            throw new IllegalArgumentException("optionType is required");
        }
        if (strikePrice == null) {
            throw new IllegalArgumentException("strikePrice is required");
        }
        if (expiry == null) {
            throw new IllegalArgumentException("expiry is required");
        }

        String exitReason = reason == null || reason.isBlank()
                ? "Manual contract close: " + contractLabel(normalizedInstrument, normalizedOptionType, strikePrice, expiry)
                : reason.trim();

        List<String> details = new ArrayList<>();
        int cancelledRequests = 0;
        int squareOffInitiated = 0;
        int skipped = 0;
        int errors = 0;

        List<TriggerTradeRequestEntity> openRequests =
                triggerTradeRequestRepository.findBySymbolIgnoreCaseAndStatusIn(normalizedInstrument, OPEN_REQUEST_STATUSES);
        for (TriggerTradeRequestEntity request : openRequests) {
            if (!matchesContract(request, normalizedOptionType, strikePrice, expiry)) {
                continue;
            }
            try {
                triggerTradeRequestRepository.delete(request);
                unsubscribe(request.getExchange(), request.getScripCode());
                cancelledRequests++;
                details.add("cancelled request #" + request.getId() + userSuffix(request.getAppUserId()));
            } catch (Exception e) {
                errors++;
                String detail = "failed cancelling request #" + request.getId() + ": " + e.getMessage();
                details.add(detail);
                log.warn(detail, e);
            }
        }

        List<TriggeredTradeSetupEntity> openTrades =
                triggeredTradeSetupRepository.findBySymbolIgnoreCaseAndStatusIn(normalizedInstrument, OPEN_EXECUTION_STATUSES);
        for (TriggeredTradeSetupEntity trade : openTrades) {
            if (!matchesContract(trade, normalizedOptionType, strikePrice, expiry)) {
                continue;
            }
            try {
                Double exitPrice = resolveExitPrice(trade, price);
                if (exitPrice == null) {
                    skipped++;
                    details.add("skipped trade #" + trade.getId() + userSuffix(trade.getAppUserId()) + ": no LTP or fallback price available");
                    continue;
                }

                tradeExecutionService.squareOff(trade, exitPrice, exitReason, TriggeredTradeStatus.EXIT_ORDER_PLACED);
                squareOffInitiated++;
                details.add("square-off initiated for trade #" + trade.getId() + userSuffix(trade.getAppUserId()));
            } catch (Exception e) {
                errors++;
                String detail = "failed square-off for trade #" + trade.getId() + ": " + e.getMessage();
                details.add(detail);
                log.warn(detail, e);
            }
        }

        return CloseTradesResponse.builder()
                .instrument(normalizedInstrument)
                .optionType(normalizedOptionType)
                .strikePrice(strikePrice)
                .expiry(expiry.toString())
                .cancelledRequests(cancelledRequests)
                .squareOffInitiated(squareOffInitiated)
                .skipped(skipped)
                .errors(errors)
                .details(details)
                .build();
    }

    private Double resolveExitPrice(TriggeredTradeSetupEntity trade, Double requestedPrice) {
        if (requestedPrice != null && requestedPrice > 0) {
            return requestedPrice;
        }
        if (trade.getScripCode() != null) {
            Double shoonyaPrice = fetchShoonyaOptionQuote(trade.getScripCode());
            if (shoonyaPrice != null) {
                return shoonyaPrice;
            }
            Double ltp = ltpCacheService.getLtp(trade.getScripCode());
            if (ltp != null && ltp > 0) {
                return ltp;
            }
        }
        if (trade.getActualEntryPrice() != null && trade.getActualEntryPrice() > 0) {
            return trade.getActualEntryPrice();
        }
        return trade.getEntryPrice() != null && trade.getEntryPrice() > 0 ? trade.getEntryPrice() : null;
    }

    private Double fetchShoonyaOptionQuote(Integer scripCode) {
        if (shoonyaQuoteService == null || scriptMasterRepository == null || scripCode == null) {
            return null;
        }
        ScriptMasterEntity script = scriptMasterRepository.findByScripCode(scripCode);
        if (!isFnoOption(script)) {
            return null;
        }
        try {
            Optional<ShoonyaQuoteService.LiveQuote> quoteOpt = shoonyaQuoteService.getOptionQuote(script);
            if (quoteOpt.isEmpty()) {
                return null;
            }
            ShoonyaQuoteService.LiveQuote quote = quoteOpt.get();
            Double price = quote.referencePrice();
            if (price == null || !Double.isFinite(price) || price <= 0d) {
                return null;
            }
            ltpCacheService.updateLtp(scripCode, price);
            return price;
        } catch (Exception e) {
            log.debug("Shoonya quote unavailable for manual close scrip {}: {}", scripCode, e.getMessage());
            return null;
        }
    }

    private boolean isFnoOption(ScriptMasterEntity script) {
        if (script == null || script.getOptionType() == null || script.getOptionType().isBlank()) {
            return false;
        }
        String exchange = script.getExchange() == null ? "" : script.getExchange().trim();
        return "NF".equalsIgnoreCase(exchange) || "NFO".equalsIgnoreCase(exchange)
                || "BF".equalsIgnoreCase(exchange) || "BFO".equalsIgnoreCase(exchange);
    }

    private void unsubscribe(String exchange, Integer scripCode) {
        if (exchange == null || exchange.isBlank() || scripCode == null) {
            return;
        }
        tradeExecutionService.releaseOptionFeedIfUnused(exchange, scripCode);
    }

    private String normalizeInstrument(String instrument) {
        if (instrument == null || instrument.isBlank()) {
            return null;
        }
        return instrument.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeOption(String optionType) {
        if (optionType == null || optionType.isBlank()) {
            return null;
        }
        String normalized = optionType.trim().toUpperCase(Locale.ROOT);
        if ("CALL".equals(normalized)) {
            return "CE";
        }
        if ("PUT".equals(normalized)) {
            return "PE";
        }
        return normalized;
    }

    private boolean matchesContract(TriggerTradeRequestEntity request,
                                    String optionType,
                                    Double strikePrice,
                                    LocalDate expiry) {
        return request != null
                && Objects.equals(optionType, normalizeOption(request.getOptionType()))
                && sameStrike(strikePrice, request.getStrikePrice())
                && Objects.equals(expiry, parseExpiry(request.getExpiry()));
    }

    private boolean matchesContract(TriggeredTradeSetupEntity trade,
                                    String optionType,
                                    Double strikePrice,
                                    LocalDate expiry) {
        return trade != null
                && Objects.equals(optionType, normalizeOption(trade.getOptionType()))
                && sameStrike(strikePrice, trade.getStrikePrice())
                && Objects.equals(expiry, parseExpiry(trade.getExpiry()));
    }

    private boolean sameStrike(Double requested, Double stored) {
        if (requested == null || stored == null) {
            return false;
        }
        return Math.abs(requested - stored) < 0.001d;
    }

    private LocalDate parseExpiry(String expiry) {
        if (expiry == null || expiry.isBlank()) {
            return null;
        }
        String trimmed = expiry.trim();
        for (DateTimeFormatter formatter : EXPIRY_FORMATS) {
            try {
                return LocalDate.parse(trimmed, formatter);
            } catch (DateTimeParseException ignored) {
            }
        }

        String upper = trimmed.toUpperCase(Locale.ROOT);
        Integer day = null;
        Integer month = null;
        Integer year = null;
        java.util.regex.Matcher dayMatcher = java.util.regex.Pattern.compile("\\b(\\d{1,2})\\b").matcher(upper);
        if (dayMatcher.find()) {
            day = Integer.parseInt(dayMatcher.group(1));
        }
        java.util.regex.Matcher yearMatcher = java.util.regex.Pattern.compile("\\b(20\\d{2})\\b").matcher(upper);
        if (yearMatcher.find()) {
            year = Integer.parseInt(yearMatcher.group(1));
        }
        for (String token : upper.split("[^A-Z]+")) {
            Integer candidateMonth = MONTHS.get(token);
            if (candidateMonth != null) {
                month = candidateMonth;
                break;
            }
        }
        if (day != null && month != null) {
            int resolvedYear = year != null ? year : LocalDate.now().getYear();
            LocalDate candidate = LocalDate.of(resolvedYear, month, day);
            if (year == null && candidate.isBefore(LocalDate.now())) {
                candidate = candidate.plusYears(1);
            }
            return candidate;
        }

        return null;
    }

    private String contractLabel(String instrument, String optionType, Double strikePrice, LocalDate expiry) {
        return instrument + " " + optionType + " " + strikePrice + " " + expiry;
    }

    private String userSuffix(Long appUserId) {
        return appUserId == null ? "" : " for user #" + appUserId;
    }
}
