package org.com.sharekhan.repository;

import org.com.sharekhan.entity.ShoonyaInstrumentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ShoonyaInstrumentRepository extends JpaRepository<ShoonyaInstrumentEntity, Long> {
    Optional<ShoonyaInstrumentEntity> findByExchangeIgnoreCaseAndTradingSymbolIgnoreCase(String exchange, String tradingSymbol);
    long deleteByExchangeIgnoreCase(String exchange);
}
