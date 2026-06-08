package org.com.sharekhan.service;

import org.com.sharekhan.entity.MStockInstrumentEntity;
import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.repository.MStockInstrumentRepository;
import org.com.sharekhan.repository.ScriptMasterRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class MStockInstrumentResolverSensexTest {

    @Test
    void resolvesSensexSpotToIndexKey() {
        MStockInstrumentResolver resolver = new MStockInstrumentResolver(
                scriptRepository(),
                null,
                mstockRepository()
        );

        Optional<String> resolved = resolver.resolveInstrumentKey(ScriptMasterEntity.builder()
                .scripCode(999901)
                .tradingSymbol("SENSEX")
                .exchange("BC")
                .instrumentType("INDEX")
                .build());

        assertThat(resolved).contains("BSE:SENSEX");
    }

    @Test
    void resolvesSensexBfoOptionToDerivativeKeyInsteadOfSpotIndexKey() {
        MStockInstrumentRepository mstockRepository = mstockRepository(
                instrument("BFO:SENSEX11JUN2674500CE", "SENSEX11JUN2674500CE", "BFO")
        );
        MStockInstrumentResolver resolver = new MStockInstrumentResolver(
                scriptRepository(),
                null,
                mstockRepository
        );

        Optional<String> resolved = resolver.resolveInstrumentKey(sensexOption());

        assertThat(resolved).contains("BFO:SENSEX11JUN2674500CE");
    }

    @Test
    void rejectsSpotIndexKeyForSensexBfoOption() {
        MStockInstrumentRepository mstockRepository = mstockRepository(
                instrument("BSE:SENSEX", "SENSEX", "BSE")
        );
        MStockInstrumentResolver resolver = new MStockInstrumentResolver(
                scriptRepository(),
                null,
                mstockRepository
        );

        Optional<String> resolved = resolver.resolveInstrumentKey(sensexOption());

        assertThat(resolved).isEmpty();
    }

    private ScriptMasterEntity sensexOption() {
        return ScriptMasterEntity.builder()
                .scripCode(1132512)
                .tradingSymbol("SENSEX")
                .exchange("BF")
                .instrumentType("OI")
                .strikePrice(74500.0)
                .expiry("11/06/2026")
                .optionType("CE")
                .build();
    }

    private MStockInstrumentEntity instrument(String key, String tradingSymbol, String exchange) {
        return MStockInstrumentEntity.builder()
                .instrumentToken((long) key.hashCode())
                .instrumentKey(key)
                .tradingSymbol(tradingSymbol)
                .name("SENSEX")
                .exchange(exchange)
                .instrumentType("CE")
                .strike(74500.0)
                .expiry("11/06/2026")
                .fetchedAt(LocalDateTime.parse("2026-06-08T09:15:30"))
                .build();
    }

    private ScriptMasterRepository scriptRepository() {
        return (ScriptMasterRepository) Proxy.newProxyInstance(
                ScriptMasterRepository.class.getClassLoader(),
                new Class<?>[]{ScriptMasterRepository.class},
                (proxy, method, args) -> defaultValue(method.getReturnType())
        );
    }

    private MStockInstrumentRepository mstockRepository(MStockInstrumentEntity... instruments) {
        Map<String, MStockInstrumentEntity> byKey = new HashMap<>();
        Map<String, MStockInstrumentEntity> byExchangeSymbol = new HashMap<>();
        for (MStockInstrumentEntity instrument : instruments) {
            byKey.put(normalize(instrument.getInstrumentKey()), instrument);
            byExchangeSymbol.put(normalize(instrument.getExchange() + ":" + instrument.getTradingSymbol()), instrument);
        }

        return (MStockInstrumentRepository) Proxy.newProxyInstance(
                MStockInstrumentRepository.class.getClassLoader(),
                new Class<?>[]{MStockInstrumentRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "count" -> (long) instruments.length;
                    case "findByInstrumentKey" -> Optional.ofNullable(byKey.get(normalize((String) args[0])));
                    case "findByExchangeAndTradingSymbol" ->
                            Optional.ofNullable(byExchangeSymbol.get(normalize(args[0] + ":" + args[1])));
                    case "findByExchangeIgnoreCaseAndNameIgnoreCase" -> List.of();
                    case "findByExchangeAndTradingSymbolPattern" -> patternMatches(instruments, (String) args[0], (String) args[1]);
                    case "existsByInstrumentKey" -> byKey.containsKey(normalize((String) args[0]));
                    case "toString" -> "MStockInstrumentRepositoryFake";
                    default -> defaultValue(method.getReturnType());
                }
        );
    }

    private List<MStockInstrumentEntity> patternMatches(MStockInstrumentEntity[] instruments, String exchange, String pattern) {
        String prefix = pattern == null ? "" : pattern.replace("%", "").toUpperCase();
        return java.util.Arrays.stream(instruments)
                .filter(i -> i.getExchange() != null && i.getExchange().equalsIgnoreCase(exchange))
                .filter(i -> i.getTradingSymbol() != null && i.getTradingSymbol().toUpperCase().startsWith(prefix))
                .toList();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase();
    }

    private Object defaultValue(Class<?> returnType) {
        if (returnType == Void.TYPE) return null;
        if (returnType == Boolean.TYPE) return false;
        if (returnType == Integer.TYPE) return 0;
        if (returnType == Long.TYPE) return 0L;
        if (returnType == List.class) return List.of();
        if (returnType == Optional.class) return Optional.empty();
        return null;
    }
}
