package org.com.sharekhan.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.entity.MStockInstrumentEntity;
import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.repository.MStockInstrumentRepository;
import org.com.sharekhan.repository.ScriptMasterRepository;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class MStockInstrumentResolver {

    private final ScriptMasterRepository scriptMasterRepository;
    private final ScriptMasterService scriptMasterService;
    private final MStockInstrumentRepository mStockInstrumentRepository;
    private final Map<Integer, String> instrumentKeyCache = new ConcurrentHashMap<>();
    private volatile Boolean instrumentMasterPopulated;

    private static final Map<String, String> API_EXCHANGE_CODES = Map.of(
            "NC", "NSE",
            "BC", "BSE",
            "NF", "NFO",
            "BF", "BFO"
    );

    private static final Map<String, String> LOOKUP_EXCHANGE_CODES = Map.of(
            "NSE", "NC",
            "BSE", "BC",
            "NFO", "NF",
            "BFO", "BF"
    );

    private static final Map<String, String> SPOT_EXCHANGE_OVERRIDES = Map.of(
            "NFO", "NSE",
            "BFO", "BSE"
    );

    private static final Map<String, String> DERIVATIVE_TO_SPOT_LOOKUP = Map.of(
            "NFO", "NC",
            "BFO", "BC"
    );

    private static final Map<String, String> BSE_FALLBACK_LOOKUP = Map.of(
            "BSE", "NC"
    );

    private static final List<DateTimeFormatter> FUTURE_EXPIRY_FORMATS = List.of(
            DateTimeFormatter.ofPattern("dd/MM/uuuu"),
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("dd-MMM-uuuu", Locale.ROOT)
    );

    public Optional<String> resolveInstrumentKey(Integer scripCode) {
        if (scripCode == null) return Optional.empty();

        ScriptMasterEntity script = scriptMasterRepository.findByScripCode(scripCode);
        String cached = instrumentKeyCache.get(scripCode);
        if (StringUtils.hasText(cached) && !shouldInvalidateCachedKey(script, cached)) {
            return Optional.of(cached);
        }
        if (StringUtils.hasText(cached)) {
            instrumentKeyCache.remove(scripCode);
        }

        Optional<String> resolved = resolveInstrumentKey(script);
        resolved.ifPresent(key -> instrumentKeyCache.put(scripCode, key));
        return resolved;
    }

    private boolean shouldInvalidateCachedKey(ScriptMasterEntity script, String cachedKey) {
        if (script == null || !StringUtils.hasText(cachedKey)) {
            return false;
        }

        if (Boolean.TRUE.equals(instrumentMasterPopulated)) {
            Optional<MStockInstrumentEntity> existing = mStockInstrumentRepository.findByInstrumentKey(cachedKey);
            if (existing.isEmpty()) {
                return true;
            }
        }

        String normalizedExchange = normalizeExchangeForApi(script.getExchange());
        boolean derivativeInstrument = isDerivativeInstrument(script, normalizedExchange);
        if (!derivativeInstrument || !StringUtils.hasText(normalizedExchange)) {
            return false;
        }

        String expectedPrefix = normalizedExchange + ":";
        if (!cachedKey.startsWith(expectedPrefix)) {
            return true;
        }

        String cachedSymbol = cachedKey.substring(expectedPrefix.length());
        return !looksLikeDerivativeSymbol(cachedSymbol);
    }

    public Optional<String> resolveInstrumentKey(ScriptMasterEntity script) {
        if (script == null) return Optional.empty();

        String normalizedExchange = normalizeExchangeForApi(script.getExchange());
        boolean derivativeInstrument = isDerivativeInstrument(script, normalizedExchange);
        if (!derivativeInstrument && isSpotInstrument(script)) {
            normalizedExchange = SPOT_EXCHANGE_OVERRIDES.getOrDefault(normalizedExchange, normalizedExchange);
        }

        if (!StringUtils.hasText(normalizedExchange)) {
            log.debug("Unable to normalize exchange '{}' for scrip {}", script.getExchange(), script.getScripCode());
            return Optional.empty();
        }

        List<String> candidateSymbols = buildCandidateSymbols(script, normalizedExchange, derivativeInstrument);
        if (candidateSymbols.isEmpty()) {
            if (derivativeInstrument) {
                log.warn("Unable to build derivative symbol for scripCode={} ({})", script.getScripCode(), script.getTradingSymbol());
                return Optional.empty();
            }
            return tryResolveViaSpotFallback(script, normalizedExchange);
        }

        for (String symbol : candidateSymbols) {
            Optional<String> key = lookupInstrumentKey(normalizedExchange, symbol);
            if (key.isPresent()) {
                return cacheAndReturn(script, key.get(), true);
            }
        }

        if (!isInstrumentMasterPopulated() && !candidateSymbols.isEmpty()) {
            String fallbackSymbol = candidateSymbols.get(0);
            String fallbackKey = buildNormalizedKey(normalizedExchange, fallbackSymbol);
            if (StringUtils.hasText(fallbackKey)) {
                log.warn("MStock instrument master not populated; using heuristic fallback {}", fallbackKey);
                return cacheAndReturn(script, fallbackKey, false);
            }
        }

        if (derivativeInstrument) {
            Optional<String> patternMatch = lookupDerivativeByPattern(normalizedExchange, script.getTradingSymbol());
            if (patternMatch.isPresent()) {
                return cacheAndReturn(script, patternMatch.get(), true);
            }
            log.warn("Unable to resolve derivative instrument for scripCode={} ({}) via MStock master.", script.getScripCode(), script.getTradingSymbol());
            return Optional.empty();
        }

        return tryResolveViaSpotFallback(script, normalizedExchange);
    }

    public Optional<ScriptMasterEntity> resolveScript(Integer scripCode,
                                                      String exchange,
                                                      String tradingSymbol,
                                                      Double strikePrice,
                                                      String optionType,
                                                      String expiry) {
        if (scripCode != null) {
            return Optional.ofNullable(scriptMasterRepository.findByScripCode(scripCode));
        }

        if (!StringUtils.hasText(exchange) || !StringUtils.hasText(tradingSymbol)) {
            return Optional.empty();
        }

        String lookupExchange = normalizeExchangeForLookup(exchange);
        String trimmedSymbol = tradingSymbol.trim();

        if (strikePrice != null && StringUtils.hasText(optionType) && StringUtils.hasText(expiry)) {
            return scriptMasterService.findOption(lookupExchange, trimmedSymbol, strikePrice, optionType, expiry);
        }

        List<ScriptMasterEntity> matches = scriptMasterRepository
                .findByExchangeIgnoreCaseAndTradingSymbolIgnoreCase(lookupExchange, trimmedSymbol);

        return matches.stream().findFirst();
    }

    private String buildSymbol(ScriptMasterEntity script, String normalizedExchange, boolean derivativeInstrument) {
        String rawSymbol = script.getTradingSymbol();
        if (!StringUtils.hasText(rawSymbol)) {
            return null;
        }

        String symbol = rawSymbol.trim().toUpperCase(Locale.ROOT);
        if (derivativeInstrument) {
            return buildDerivativeSymbol(script, symbol);
        }
        if (isSpotInstrument(script)) {
            String suffix = deriveSpotSeriesSuffix(script, normalizedExchange);
            if (StringUtils.hasText(suffix)) {
                String upperSuffix = suffix.toUpperCase(Locale.ROOT);
                if (!symbol.endsWith("-" + upperSuffix)) {
                    symbol = symbol + "-" + upperSuffix;
                }
            }
        }

        return symbol;
    }

    private List<String> buildCandidateSymbols(ScriptMasterEntity script,
                                               String normalizedExchange,
                                               boolean derivativeInstrument) {
        List<String> candidates = new ArrayList<>();
        String built = buildSymbol(script, normalizedExchange, derivativeInstrument);

        if (derivativeInstrument) {
            if (StringUtils.hasText(built)) {
                if (!looksLikeDerivativeSymbol(built)) {
                    log.warn("Resolved symbol {} for derivative scripCode={} does not resemble a derivative symbol; skipping.", built, script.getScripCode());
                } else {
                    candidates.add(built);
                }
            }
            return candidates;
        }

        String baseSymbol = script.getTradingSymbol() != null
                ? script.getTradingSymbol().trim().toUpperCase(Locale.ROOT)
                : null;

        if (StringUtils.hasText(built)) {
            candidates.add(built);
        }
        if (StringUtils.hasText(baseSymbol) && !candidates.contains(baseSymbol)) {
            candidates.add(baseSymbol);
        }

        // For spot symbols, also consider variants without hyphen.
        if (StringUtils.hasText(built) && built.contains("-")) {
            String withoutHyphen = built.replace("-", "");
            if (!candidates.contains(withoutHyphen)) {
                candidates.add(withoutHyphen);
            }
        }

        return candidates;
    }

    private Optional<String> lookupInstrumentKey(String exchange, String symbol) {
        if (!StringUtils.hasText(exchange) || !StringUtils.hasText(symbol)) {
            return Optional.empty();
        }
        String candidateKey = buildNormalizedKey(exchange, symbol);
        if (!StringUtils.hasText(candidateKey)) {
            return Optional.empty();
        }

        Optional<MStockInstrumentEntity> direct = mStockInstrumentRepository.findByInstrumentKey(candidateKey);
        if (direct.isPresent()) {
            return Optional.of(direct.get().getInstrumentKey());
        }

        int colon = candidateKey.indexOf(':');
        String normalizedExchange = candidateKey.substring(0, colon);
        String normalizedSymbol = candidateKey.substring(colon + 1);
        Optional<MStockInstrumentEntity> bySymbol = mStockInstrumentRepository
                .findByExchangeAndTradingSymbol(normalizedExchange, normalizedSymbol);
        return bySymbol.map(MStockInstrumentEntity::getInstrumentKey);
    }

    private Optional<String> lookupDerivativeByPattern(String exchange, String tradingSymbol) {
        if (!StringUtils.hasText(exchange) || !StringUtils.hasText(tradingSymbol)) {
            return Optional.empty();
        }
        String normalizedExchange = exchange.trim().toUpperCase(Locale.ROOT);
        String base = tradingSymbol.trim().toUpperCase(Locale.ROOT);
        List<String> patterns = new ArrayList<>();
        patterns.add(base + "%");
        String collapsed = base.replaceAll("[^A-Z0-9]", "");
        if (!collapsed.equals(base)) {
            patterns.add(collapsed + "%");
        }

        for (String pattern : patterns) {
            List<MStockInstrumentEntity> matches = mStockInstrumentRepository
                    .findByExchangeAndTradingSymbolPattern(normalizedExchange, pattern);
            Optional<String> first = matches.stream()
                    .map(MStockInstrumentEntity::getInstrumentKey)
                    .findFirst();
            if (first.isPresent()) {
                return first;
            }
        }
        return Optional.empty();
    }

    private String buildNormalizedKey(String exchange, String symbol) {
        if (!StringUtils.hasText(exchange) || !StringUtils.hasText(symbol)) {
            return null;
        }
        return exchange.trim().toUpperCase(Locale.ROOT) + ":" + symbol.trim().toUpperCase(Locale.ROOT);
    }

    private boolean isInstrumentMasterPopulated() {
        if (Boolean.TRUE.equals(instrumentMasterPopulated)) {
            return true;
        }
        long count = mStockInstrumentRepository.count();
        boolean populated = count > 0;
        instrumentMasterPopulated = populated;
        return populated;
    }

    private Optional<String> cacheAndReturn(ScriptMasterEntity script, String key, boolean fromMaster) {
        if (fromMaster) {
            instrumentMasterPopulated = true;
        }
        if (script != null && script.getScripCode() != null && StringUtils.hasText(key)) {
            instrumentKeyCache.put(script.getScripCode(), key);
        }
        return Optional.ofNullable(key);
    }
    
    private String buildDerivativeSymbol(ScriptMasterEntity script, String baseSymbol) {
        if (isFutureInstrument(script)) {
            LocalDate expiryDate = parseExpiry(script.getExpiry());
            if (expiryDate == null) {
                log.warn("Unable to parse expiry '{}' for future scripCode={}", script.getExpiry(), script.getScripCode());
                return null;
            }
            String yearSuffix = String.format(Locale.ROOT, "%02d", expiryDate.getYear() % 100);
            String monthCode = expiryDate.getMonth().name().substring(0, 3);
            return baseSymbol + yearSuffix + monthCode + "FUT";
        }
        if (isOptionInstrument(script)) {
            return buildOptionSymbol(script, baseSymbol);
        }
        return null;
    }

    private String buildOptionSymbol(ScriptMasterEntity script, String baseSymbol) {
        LocalDate expiryDate = parseExpiry(script.getExpiry());
        if (expiryDate == null) {
            log.warn("Unable to parse expiry '{}' for option scripCode={}", script.getExpiry(), script.getScripCode());
            return null;
        }

        String strikeComponent = formatStrike(script.getStrikePrice());
        if (!StringUtils.hasText(strikeComponent)) {
            log.warn("Unable to format strike '{}' for option scripCode={}", script.getStrikePrice(), script.getScripCode());
            return null;
        }

        String optionType = script.getOptionType();
        if (!StringUtils.hasText(optionType)) {
            log.warn("Missing option type for scripCode={}", script.getScripCode());
            return null;
        }

        String day = String.format(Locale.ROOT, "%02d", expiryDate.getDayOfMonth());
        String monthCode = expiryDate.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH).toUpperCase(Locale.ROOT);
        String yearSuffix = String.format(Locale.ROOT, "%02d", expiryDate.getYear() % 100);

        return baseSymbol
                + day
                + monthCode
                + yearSuffix
                + strikeComponent
                + optionType.trim().toUpperCase(Locale.ROOT);
    }

    private String formatStrike(Double strikePrice) {
        if (strikePrice == null) {
            return null;
        }
        BigDecimal strike = BigDecimal.valueOf(strikePrice).stripTrailingZeros();
        if (strike.signum() <= 0) {
            return null;
        }
        String strikeText = strike.toPlainString();
        return strikeText.contains(".") ? strikeText.replace(".", "") : strikeText;
    }
    
    private boolean isFutureInstrument(ScriptMasterEntity script) {
        if (script == null) return false;
        if (!StringUtils.hasText(script.getOptionType())) return false;
        if (!"FUT".equalsIgnoreCase(script.getOptionType())) return false;
        String type = script.getInstrumentType();
        boolean futureType = StringUtils.hasText(type) &&
                (type.equalsIgnoreCase("FI") || type.equalsIgnoreCase("FS"));
        return futureType && StringUtils.hasText(script.getExpiry());
    }

    private boolean isOptionInstrument(ScriptMasterEntity script) {
        if (script == null) return false;
        String optionType = script.getOptionType();
        if (!StringUtils.hasText(optionType)) {
            return false;
        }
        String normalized = optionType.trim().toUpperCase(Locale.ROOT);
        return "CE".equals(normalized) || "PE".equals(normalized);
    }

    private boolean isDerivativeInstrument(ScriptMasterEntity script, String normalizedExchange) {
        if (script == null) {
            return false;
        }
        if (isFutureInstrument(script) || isOptionInstrument(script)) {
            return true;
        }
        return isDerivativeExchange(normalizedExchange);
    }
    
    private LocalDate parseExpiry(String expiry) {
        if (!StringUtils.hasText(expiry)) {
            return null;
        }
        String trimmed = expiry.trim();
        for (DateTimeFormatter formatter : FUTURE_EXPIRY_FORMATS) {
            try {
                return LocalDate.parse(trimmed, formatter);
            } catch (DateTimeParseException ignored) {
            }
        }
        if (trimmed.length() >= 10) {
            try {
                return LocalDate.parse(trimmed.substring(0, 10), DateTimeFormatter.ISO_LOCAL_DATE);
            } catch (DateTimeParseException ignored) {
            }
        }
        return null;
    }

    private boolean isSpotInstrument(ScriptMasterEntity script) {
        if (script == null) return false;
        boolean noStrike = script.getStrikePrice() == null || Math.abs(script.getStrikePrice()) < 0.0001;
        boolean noExpiry = !StringUtils.hasText(script.getExpiry());
        String instrumentType = script.getInstrumentType();
        boolean isEquityType = !StringUtils.hasText(instrumentType) ||
                instrumentType.equalsIgnoreCase("EQ") ||
                instrumentType.equalsIgnoreCase("EQUITY");
        return noStrike && noExpiry && isEquityType;
    }

    private String deriveSpotSeriesSuffix(ScriptMasterEntity script, String normalizedExchange) {
        String rawSeries = script.getOptionType();
        if (StringUtils.hasText(rawSeries)) {
            return rawSeries.trim().toUpperCase(Locale.ROOT);
        }
        if ("NSE".equals(normalizedExchange)) {
            return "EQ";
        }
        if ("BSE".equals(normalizedExchange)) {
            return "A";
        }
        return null;
    }

    private Optional<ScriptMasterEntity> findAlternateSpotScript(String tradingSymbol, String exchangeCode) {
        if (!StringUtils.hasText(tradingSymbol) || !StringUtils.hasText(exchangeCode)) {
            return Optional.empty();
        }

        List<ScriptMasterEntity> matches = scriptMasterRepository
                .findByExchangeIgnoreCaseAndTradingSymbolIgnoreCase(exchangeCode, tradingSymbol.trim());
        return matches.stream()
                .filter(this::isSpotInstrument)
                .findFirst();
    }

    private Optional<String> tryResolveViaSpotFallback(ScriptMasterEntity script, String normalizedExchange) {
        String candidateExchange = DERIVATIVE_TO_SPOT_LOOKUP.get(normalizedExchange);
        if (!StringUtils.hasText(candidateExchange)) {
            candidateExchange = BSE_FALLBACK_LOOKUP.get(normalizedExchange);
        }
        if (!StringUtils.hasText(candidateExchange)) {
            return Optional.empty();
        }
        Optional<ScriptMasterEntity> alternate = findAlternateSpotScript(script.getTradingSymbol(), candidateExchange);
        if (alternate.isPresent()) {
            Optional<String> alternateKey = resolveInstrumentKey(alternate.get());
            alternateKey.ifPresent(key -> {
                if (script.getScripCode() != null) {
                    instrumentKeyCache.put(script.getScripCode(), key);
                }
            });
            return alternateKey;
        }
        return Optional.empty();
    }

    private boolean isDerivativeExchange(String exchange) {
        return exchange != null && (exchange.equalsIgnoreCase("NFO") || exchange.equalsIgnoreCase("BFO"));
    }

    private boolean looksLikeDerivativeSymbol(String symbol) {
        if (!StringUtils.hasText(symbol)) return false;
        boolean hasDigits = symbol.chars().anyMatch(Character::isDigit);
        if (!hasDigits) return false;
        String upper = symbol.toUpperCase(Locale.ROOT);
        return upper.contains("CE") || upper.contains("PE") || upper.contains("FUT");
    }

    private String normalizeExchangeForApi(String exchange) {
        if (!StringUtils.hasText(exchange)) return null;
        String trimmed = exchange.trim().toUpperCase(Locale.ROOT);
        return API_EXCHANGE_CODES.getOrDefault(trimmed, trimmed);
    }

    private String normalizeExchangeForLookup(String exchange) {
        if (!StringUtils.hasText(exchange)) return null;
        String trimmed = exchange.trim().toUpperCase(Locale.ROOT);
        return LOOKUP_EXCHANGE_CODES.getOrDefault(trimmed, trimmed);
    }
}
