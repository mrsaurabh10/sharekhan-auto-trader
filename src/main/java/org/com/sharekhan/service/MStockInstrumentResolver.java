package org.com.sharekhan.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.repository.ScriptMasterRepository;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class MStockInstrumentResolver {

    private final ScriptMasterRepository scriptMasterRepository;
    private final ScriptMasterService scriptMasterService;
    private final Map<Integer, String> instrumentKeyCache = new ConcurrentHashMap<>();

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

    public Optional<String> resolveInstrumentKey(Integer scripCode) {
        if (scripCode == null) return Optional.empty();
        String cached = instrumentKeyCache.get(scripCode);
        if (StringUtils.hasText(cached)) {
            return Optional.of(cached);
        }
        ScriptMasterEntity script = scriptMasterRepository.findByScripCode(scripCode);
        Optional<String> resolved = resolveInstrumentKey(script);
        resolved.ifPresent(key -> instrumentKeyCache.put(scripCode, key));
        return resolved;
    }

    public Optional<String> resolveInstrumentKey(ScriptMasterEntity script) {
        if (script == null) return Optional.empty();

        String normalizedExchange = normalizeExchangeForApi(script.getExchange());
        if (isSpotInstrument(script)) {
            normalizedExchange = SPOT_EXCHANGE_OVERRIDES.getOrDefault(normalizedExchange, normalizedExchange);
        }
        if (!StringUtils.hasText(normalizedExchange)) {
            log.debug("Unable to normalize exchange '{}' for scrip {}", script.getExchange(), script.getScripCode());
            return Optional.empty();
        }

        String symbol = buildSymbol(script, normalizedExchange);
        if (!StringUtils.hasText(symbol)) {
            return tryResolveViaSpotFallback(script, normalizedExchange);
        }

        if (isDerivativeExchange(normalizedExchange) && !looksLikeDerivativeSymbol(symbol)) {
            Optional<String> spotKey = tryResolveViaSpotFallback(script, normalizedExchange);
            if (spotKey.isPresent()) {
                return spotKey;
            }
            log.debug("Skipping derivative instrument {} due to missing expiry/series data", script.getTradingSymbol());
            return Optional.empty();
        }

        String key = normalizedExchange + ":" + symbol;
        if (script.getScripCode() != null) {
            instrumentKeyCache.put(script.getScripCode(), key);
        }
        return Optional.of(key);
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

    private String buildSymbol(ScriptMasterEntity script, String normalizedExchange) {
        String rawSymbol = script.getTradingSymbol();
        if (!StringUtils.hasText(rawSymbol)) {
            return null;
        }

        String symbol = rawSymbol.trim().toUpperCase(Locale.ROOT);
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
