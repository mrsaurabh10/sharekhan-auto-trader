// ScriptMasterRepository.java
package org.com.sharekhan.repository;

import org.com.sharekhan.entity.ScriptMasterEntity;
import org.springframework.data.jpa.repository.JpaRepository;

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
}