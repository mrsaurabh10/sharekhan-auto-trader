package org.com.sharekhan.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.repository.ScriptMasterRepository;
import org.springframework.util.StringUtils;

/** Price alignment using the broker's instrument identity and published tick. */
public final class SharekhanTickPrices {
    private static final BigDecimal DEFAULT_OPTION_TICK = new BigDecimal("0.05");

    private SharekhanTickPrices() { }

    public static BigDecimal tickSize(ScriptMasterRepository repository, Integer scripCode, String exchange) {
        if (scripCode == null || !StringUtils.hasText(exchange)) {
            throw new IllegalArgumentException("Missing Sharekhan instrument identity");
        }
        return repository.findById(scripCode)
                .filter(script -> exchange.equalsIgnoreCase(script.getExchange()))
                .map(SharekhanTickPrices::publishedOrOptionTick)
                .orElseThrow(() -> new IllegalArgumentException("No valid Sharekhan tick for " + exchange + ":" + scripCode));
    }

    private static BigDecimal publishedOrOptionTick(ScriptMasterEntity script) {
        Double tick = script.getTickSize();
        if (tick != null && Double.isFinite(tick) && tick > 0d) {
            return BigDecimal.valueOf(tick);
        }
        // Sharekhan can omit ticks for BF options. Only a confirmed option may use this fallback.
        if ("CE".equalsIgnoreCase(script.getOptionType()) || "PE".equalsIgnoreCase(script.getOptionType())) {
            return DEFAULT_OPTION_TICK;
        }
        return null;
    }

    public static double round(double price, BigDecimal tickSize) {
        return BigDecimal.valueOf(price).divide(tickSize, 0, RoundingMode.HALF_UP)
                .multiply(tickSize).doubleValue();
    }

    public static double round(ScriptMasterRepository repository, Integer scripCode, String exchange, double price) {
        return price == 0d ? 0d : round(price, tickSize(repository, scripCode, exchange));
    }
}
