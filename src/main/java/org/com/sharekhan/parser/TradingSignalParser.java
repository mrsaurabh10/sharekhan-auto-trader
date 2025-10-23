package org.com.sharekhan.parser;

import java.util.Map;

public interface TradingSignalParser {
    Map<String, Object> parse(String text);
}
