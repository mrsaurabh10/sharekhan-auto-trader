package org.com.sharekhan.parser;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class ParserChain implements TradingSignalParser {

    private final List<TradingSignalParser> parsers;

    public ParserChain(TradingSignalParser... parsers) {
        this.parsers = Arrays.asList(parsers);
    }

    @Override
    public Map<String, Object> parse(String text) {
        for (TradingSignalParser parser : parsers) {
            Map<String, Object> result = parser.parse(text);
            if (result != null && !result.isEmpty()) {
                return result;
            }
        }
        return null; // none matched
    }
}
