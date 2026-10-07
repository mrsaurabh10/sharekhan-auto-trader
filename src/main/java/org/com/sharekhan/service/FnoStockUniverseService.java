package org.com.sharekhan.service;

import lombok.RequiredArgsConstructor;
import org.com.sharekhan.repository.ScriptMasterRepository;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Resolves Marketaux exchange-qualified symbols to F&O stock underlyings in the broker master. */
@Service
@RequiredArgsConstructor
public class FnoStockUniverseService {
    private final ScriptMasterRepository scriptMasterRepository;

    public Optional<String> resolveFnoStockUnderlying(String marketauxSymbol) {
        String canonical = canonicalize(marketauxSymbol);
        if (canonical == null) {
            return Optional.empty();
        }
        return fnoStockUnderlyings().contains(canonical) ? Optional.of(canonical) : Optional.empty();
    }

    private Set<String> fnoStockUnderlyings() {
        return scriptMasterRepository.findDistinctOptionStockUnderlyingSymbols().stream()
                .filter(StringUtils::hasText)
                .map(value -> value.trim().toUpperCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    private String canonicalize(String marketauxSymbol) {
        if (!StringUtils.hasText(marketauxSymbol)) {
            return null;
        }
        String value = marketauxSymbol.trim().toUpperCase(Locale.ROOT);
        if (value.startsWith("^")) {
            return null; // Marketaux index symbols, such as ^NSEI, are not stock F&O underlyings.
        }
        value = value.replaceFirst("\\.(NS|BO)$", "");
        value = value.replaceFirst("-BL$", ""); // Marketaux alternate NSE listing alias, e.g. HCLTECH-BL.NS.
        return value.isBlank() ? null : value;
    }
}
