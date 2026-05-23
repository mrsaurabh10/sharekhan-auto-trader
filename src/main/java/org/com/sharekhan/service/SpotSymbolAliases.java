package org.com.sharekhan.service;

import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class SpotSymbolAliases {

    private static final Map<String, String> ALIASES = Map.of(
            "BANKNIFTY", "NiftyBank"
    );

    private SpotSymbolAliases() {
    }

    static List<String> candidates(String symbol) {
        if (!StringUtils.hasText(symbol)) {
            return List.of();
        }
        String trimmed = symbol.trim();
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        candidates.add(trimmed);

        String alias = ALIASES.get(trimmed.toUpperCase(Locale.ROOT));
        if (StringUtils.hasText(alias)) {
            candidates.add(alias);
        }
        return List.copyOf(candidates);
    }
}
