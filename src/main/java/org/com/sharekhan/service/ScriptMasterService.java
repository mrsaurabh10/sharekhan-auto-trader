package org.com.sharekhan.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.repository.ScriptMasterRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ScriptMasterService {

    private final ScriptMasterRepository repository;

    public List<String> getAllExchanges() {
        return repository.findDistinctExchanges();
    }

    public List<String> getInstrumentsForExchange(String exchange) {
        if (exchange == null) return List.of();
        String ex = exchange.toUpperCase().trim();
        log.debug("getInstrumentsForExchange called with exchange='{}' (normalized='{}')", exchange, ex);
        if (ex.equals("NC") || ex.equals("BC")) {
            // Try case-insensitive strict NULL-match first (handles 'nc', ' Nc ', etc.)
            List<ScriptMasterEntity> list = repository.findByExchangeAndStrikePriceIsNullAndExpiryIsNullIgnoreCase(ex);
            log.debug("strict-null-match list size for {} = {}", ex, list == null ? null : list.size());
            if (list == null || list.isEmpty()) {
                // fallback: try a case-insensitive fetch of all rows for the exchange
                List<ScriptMasterEntity> allForExchange = repository.findByExchangeIgnoreCase(ex);
                final List<ScriptMasterEntity> all = (allForExchange == null) ? List.of() : allForExchange;
                log.debug("allForExchange size (ignoreCase) for {} = {}", ex, all.size());
                List<String> filtered = all.stream()
                        .filter(s -> (s.getStrikePrice() == null || Double.compare(s.getStrikePrice(), 0.0) == 0)
                                && (s.getExpiry() == null || s.getExpiry().isBlank()))
                        .map(ScriptMasterEntity::getTradingSymbol)
                        .distinct()
                        .toList();
                log.debug("filtered (NULL or 0.0 & blank expiry) size for {} = {}", ex, filtered.size());
                if (!filtered.isEmpty()) return filtered;

                // Final fallback: return any trading symbols for the exchange to avoid empty responses
                if (!all.isEmpty()) {
                    List<String> any = all.stream()
                            .map(ScriptMasterEntity::getTradingSymbol)
                            .distinct()
                            .toList();
                    log.debug("returning any trading symbols for {} size = {}", ex, any.size());
                    return any;
                }

                // As a last effort, try the original exact-match finder
                List<String> exact = repository.findByExchange(ex).stream()
                        .map(ScriptMasterEntity::getTradingSymbol)
                        .distinct()
                        .toList();
                log.debug("exact-match fallback size for {} = {}", ex, exact.size());
                return exact;
            }

            return list.stream()
                    .map(ScriptMasterEntity::getTradingSymbol)
                    .distinct()
                    .toList();
        }

        return repository.findByExchange(exchange).stream()
                .map(ScriptMasterEntity::getTradingSymbol)
                .distinct()
                .toList();
    }

    public List<String> getStrikesForInstrument(String exchange, String instrument) {
        return repository.findStrikePrices(exchange, instrument);
    }

    public List<String> getExpiries(String exchange, String instrument, Double strike) {
        return repository.findByExchangeAndTradingSymbolAndStrikePrice(exchange, instrument, strike).stream()
                .map(ScriptMasterEntity::getExpiry)
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    /**
     * Find a specific option script for the provided selection.
     * Returns Optional.empty() if not found.
     */
    public Optional<ScriptMasterEntity> findOption(String exchange, String instrument, Double strikePrice, String optionType, String expiry) {
        if (instrument == null || strikePrice == null || optionType == null || expiry == null) {
            return Optional.empty();
        }

        Optional<ScriptMasterEntity> opt = repository.findByTradingSymbolAndStrikePriceAndOptionTypeAndExpiry(
                instrument, strikePrice, optionType, expiry
        );

        if (opt.isPresent()) {
            ScriptMasterEntity e = opt.get();
            if (exchange == null || exchange.isBlank() || exchange.equals(e.getExchange())) {
                return Optional.of(e);
            }
        }

        // fallback: try to find among scripts for the exchange + instrument + strike and match optionType & expiry
        List<ScriptMasterEntity> list = repository.findByExchangeAndTradingSymbolAndStrikePrice(exchange, instrument, strikePrice);
        return list.stream()
                .filter(e -> optionType.equalsIgnoreCase(e.getOptionType()) && expiry.equals(e.getExpiry()))
                .findFirst();
    }

    // Debug helper: fetch raw scripts for an exchange (case-insensitive)
    public List<ScriptMasterEntity> getRawScriptsForExchange(String exchange) {
        if (exchange == null) return List.of();
        String ex = exchange.toUpperCase().trim();
        // Try ignore-case fetch first
        List<ScriptMasterEntity> raw = repository.findByExchangeIgnoreCase(ex);
        if (raw != null && !raw.isEmpty()) return raw;
        // Fallback to exact
        return repository.findByExchange(exchange);
    }
}