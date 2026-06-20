package org.com.sharekhan.controller;

import lombok.RequiredArgsConstructor;
import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.repository.ScriptMasterRepository;
import org.com.sharekhan.service.ScriptMasterService;
import org.com.sharekhan.service.SharekhanHistoricalService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/sharekhan/historical")
@RequiredArgsConstructor
public class SharekhanHistoricalController {

    private static final String DEFAULT_INTERVAL = "5minute";
    private static final Pattern INTERVAL_PATTERN = Pattern.compile("[A-Za-z0-9_-]+");

    private final SharekhanHistoricalService historicalService;
    private final ScriptMasterRepository scriptMasterRepository;
    private final ScriptMasterService scriptMasterService;

    @GetMapping("/candles")
    public ResponseEntity<?> getHistoricalCandles(
            @RequestParam(name = "scripCode", required = false) Integer scripCode,
            @RequestParam(name = "exchange", required = false) String exchange,
            @RequestParam(name = "instrument", required = false) String instrument,
            @RequestParam(name = "strikePrice", required = false) Double strikePrice,
            @RequestParam(name = "optionType", required = false) String optionType,
            @RequestParam(name = "expiry", required = false) String expiry,
            @RequestParam(name = "interval", required = false) String interval,
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        String intervalSegment = normalizeInterval(interval);
        if (!StringUtils.hasText(intervalSegment)) {
            return badRequest("interval must contain only letters, digits, underscore, or hyphen, e.g. 5minute");
        }
        if ((from == null) != (to == null)) {
            return badRequest("Provide both from and to dates, or omit both. Use ISO format yyyy-MM-dd.");
        }
        if (from != null && from.isAfter(to)) {
            return badRequest("from date must be on or before to date.");
        }
        if (hasPartialOptionLookup(strikePrice, optionType, expiry)) {
            return badRequest("For option lookup, provide strikePrice, optionType, and expiry together.");
        }

        Optional<ScriptMasterEntity> scriptOpt = resolveScript(
                scripCode, exchange, instrument, strikePrice, optionType, expiry);
        if (scriptOpt.isEmpty()) {
            Map<String, Object> err = error("Unable to locate script in cache. Provide scripCode, or exchange + instrument. If needed, refresh Sharekhan script master with POST /api/scripts/refresh.");
            return ResponseEntity.status(404).body(err);
        }

        ScriptMasterEntity script = scriptOpt.get();
        List<SharekhanHistoricalService.HistoricalCandle> candles = historicalService.getHistoricalCandles(
                script.getScripCode(), intervalSegment, from, to);

        return ResponseEntity.ok(new HistoricalCandlesResponse(
                "success",
                script.getScripCode(),
                script.getTradingSymbol(),
                script.getExchange(),
                intervalSegment,
                from,
                to,
                candles.size(),
                candles
        ));
    }

    private Optional<ScriptMasterEntity> resolveScript(Integer scripCode,
                                                       String exchange,
                                                       String instrument,
                                                       Double strikePrice,
                                                       String optionType,
                                                       String expiry) {
        if (scripCode != null) {
            return Optional.ofNullable(scriptMasterRepository.findByScripCode(scripCode));
        }
        if (!StringUtils.hasText(exchange) || !StringUtils.hasText(instrument)) {
            return Optional.empty();
        }

        String normalizedExchange = exchange.trim().toUpperCase();
        String normalizedInstrument = instrument.trim();
        if (strikePrice != null && StringUtils.hasText(optionType) && StringUtils.hasText(expiry)) {
            return scriptMasterService.findOption(
                    normalizedExchange, normalizedInstrument, strikePrice, optionType.trim(), expiry.trim());
        }

        return scriptMasterRepository
                .findByExchangeIgnoreCaseAndTradingSymbolIgnoreCase(normalizedExchange, normalizedInstrument)
                .stream()
                .findFirst();
    }

    private String normalizeInterval(String interval) {
        String value = StringUtils.hasText(interval) ? interval.trim() : DEFAULT_INTERVAL;
        return INTERVAL_PATTERN.matcher(value).matches() ? value : null;
    }

    private boolean hasPartialOptionLookup(Double strikePrice, String optionType, String expiry) {
        boolean hasStrike = strikePrice != null;
        boolean hasOptionType = StringUtils.hasText(optionType);
        boolean hasExpiry = StringUtils.hasText(expiry);
        return (hasStrike || hasOptionType || hasExpiry) && !(hasStrike && hasOptionType && hasExpiry);
    }

    private ResponseEntity<Map<String, Object>> badRequest(String message) {
        return ResponseEntity.badRequest().body(error(message));
    }

    private Map<String, Object> error(String message) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("status", "error");
        err.put("message", message);
        return err;
    }

    public record HistoricalCandlesResponse(String status,
                                            Integer scripCode,
                                            String tradingSymbol,
                                            String exchange,
                                            String interval,
                                            LocalDate from,
                                            LocalDate to,
                                            int count,
                                            List<SharekhanHistoricalService.HistoricalCandle> candles) {
    }
}
