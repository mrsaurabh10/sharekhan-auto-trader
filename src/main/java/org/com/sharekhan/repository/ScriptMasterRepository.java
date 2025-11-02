// ScriptMasterRepository.java
package org.com.sharekhan.repository;

import org.com.sharekhan.entity.ScriptMasterEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ScriptMasterRepository extends JpaRepository<ScriptMasterEntity, Integer> {
    Optional<ScriptMasterEntity> findByTradingSymbol(String tradingSymbol);

    Optional<ScriptMasterEntity> findByTradingSymbolAndStrikePriceAndOptionTypeAndExpiry(
            String tradingSymbol,
            Double strikePrice,
            String optionType,
            String expiry
    );


    void deleteAll();

    @Query("SELECT DISTINCT s.exchange FROM ScriptMasterEntity s")
    List<String> findDistinctExchanges();

    @Query("SELECT s.expiry FROM ScriptMasterEntity s WHERE s.tradingSymbol = :symbol AND s.strikePrice = :strikePrice AND s.optionType = :optionType")
    List<String> findAllExpiriesByTradingSymbolAndStrikePriceAndOptionType(
            @Param("symbol") String symbol,
            @Param("strikePrice") Double strikePrice,
            @Param("optionType") String optionType
    );

    List<ScriptMasterEntity> findByExchange(String exchange);

    @Query("SELECT DISTINCT s.strikePrice FROM ScriptMasterEntity s WHERE s.exchange = :exchange AND s.tradingSymbol = :instrument AND s.strikePrice IS NOT NULL")
    List<String> findStrikePrices(@Param("exchange") String exchange, @Param("instrument") String instrument);

    List<ScriptMasterEntity> findByExchangeAndTradingSymbolAndStrikePrice(String exchange, String symbol, Double strikePrice);

    // Find script where expiry IS NULL (used for equities where strikePrice == 0.0)
    @Query("SELECT s FROM ScriptMasterEntity s WHERE s.tradingSymbol = :symbol AND s.strikePrice = :strikePrice AND s.expiry IS NULL AND (:optionType IS NULL OR s.optionType = :optionType)")
    Optional<ScriptMasterEntity> findByTradingSymbolAndStrikePriceAndExpiryIsNull(
            @Param("symbol") String symbol,
            @Param("strikePrice") Double strikePrice,
            @Param("optionType") String optionType
    );

    // Find all scripts for an exchange where strikePrice IS NULL and expiry IS NULL (used for NC/BC instrument list)
    @Query("SELECT s FROM ScriptMasterEntity s WHERE s.exchange = :exchange AND s.strikePrice IS NULL AND s.expiry IS NULL")
    List<ScriptMasterEntity> findByExchangeAndStrikePriceIsNullAndExpiryIsNull(@Param("exchange") String exchange);

    // Case-insensitive variant (handles 'nc', 'Nc', trailing spaces, etc.)
    @Query("SELECT s FROM ScriptMasterEntity s WHERE UPPER(TRIM(s.exchange)) = :exchangeUpper AND s.strikePrice IS NULL AND s.expiry IS NULL")
    List<ScriptMasterEntity> findByExchangeAndStrikePriceIsNullAndExpiryIsNullIgnoreCase(@Param("exchangeUpper") String exchangeUpper);

    @Query("SELECT s FROM ScriptMasterEntity s WHERE UPPER(TRIM(s.exchange)) = :exchangeUpper")
    List<ScriptMasterEntity> findByExchangeIgnoreCase(@Param("exchangeUpper") String exchangeUpper);

    // Find script where strikePrice IS NULL and expiry IS NULL for a specific exchange (used for NC/BC equities)
    @Query("SELECT s FROM ScriptMasterEntity s WHERE s.exchange = :exchange AND s.tradingSymbol = :symbol AND s.strikePrice IS NULL AND s.expiry IS NULL")
    Optional<ScriptMasterEntity> findByExchangeAndTradingSymbolAndStrikePriceIsNullAndExpiryIsNull(
            @Param("exchange") String exchange,
            @Param("symbol") String symbol
    );
}