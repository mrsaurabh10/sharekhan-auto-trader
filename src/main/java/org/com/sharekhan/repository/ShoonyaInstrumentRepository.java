package org.com.sharekhan.repository;

import org.com.sharekhan.entity.ShoonyaInstrumentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ShoonyaInstrumentRepository extends JpaRepository<ShoonyaInstrumentEntity, Long> {
    Optional<ShoonyaInstrumentEntity> findByExchangeIgnoreCaseAndTradingSymbolIgnoreCase(String exchange, String tradingSymbol);
    Optional<ShoonyaInstrumentEntity> findFirstByExchangeIgnoreCaseAndSymbolIgnoreCaseAndExpiryIgnoreCaseAndOptionTypeIgnoreCaseAndStrikePrice(
            String exchange, String symbol, String expiry, String optionType, Double strikePrice);
    long deleteByExchangeIgnoreCase(String exchange);
}
