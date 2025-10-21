package org.com.sharekhan.service;

import lombok.RequiredArgsConstructor;
import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.repository.ScriptMasterRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ScriptMasterService {

    private final ScriptMasterRepository repository;

    public List<String> getAllExchanges() {
        return repository.findDistinctExchanges();
    }

    public List<String> getInstrumentsForExchange(String exchange) {
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
}