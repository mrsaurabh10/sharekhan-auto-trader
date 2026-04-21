package org.com.sharekhan.repository;

import org.com.sharekhan.entity.MStockInstrumentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MStockInstrumentRepository extends JpaRepository<MStockInstrumentEntity, Long> {

    @Query("SELECT i FROM MStockInstrumentEntity i WHERE UPPER(i.instrumentKey) = UPPER(:instrumentKey)")
    Optional<MStockInstrumentEntity> findByInstrumentKey(@Param("instrumentKey") String instrumentKey);

    @Query("SELECT i FROM MStockInstrumentEntity i WHERE UPPER(i.exchange) = UPPER(:exchange) AND UPPER(i.tradingSymbol) = UPPER(:tradingSymbol)")
    Optional<MStockInstrumentEntity> findByExchangeAndTradingSymbol(
            @Param("exchange") String exchange,
            @Param("tradingSymbol") String tradingSymbol
    );

    @Query("SELECT i FROM MStockInstrumentEntity i WHERE UPPER(i.exchange) = UPPER(:exchange) AND UPPER(i.tradingSymbol) LIKE UPPER(:pattern)")
    List<MStockInstrumentEntity> findByExchangeAndTradingSymbolPattern(
            @Param("exchange") String exchange,
            @Param("pattern") String tradingSymbolPattern
    );

    List<MStockInstrumentEntity> findByExchangeIgnoreCaseAndNameIgnoreCase(String exchange, String name);

    boolean existsByInstrumentKey(String instrumentKey);
}
