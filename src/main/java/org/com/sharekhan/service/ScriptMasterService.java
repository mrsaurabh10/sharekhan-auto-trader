package org.com.sharekhan.service;

import lombok.RequiredArgsConstructor;
import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.repository.ScriptMasterRepository;
import org.springframework.stereotype.Service;

import java.util.List;
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
}