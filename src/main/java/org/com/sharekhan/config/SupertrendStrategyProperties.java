package org.com.sharekhan.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.strategy.supertrend")
public class SupertrendStrategyProperties {
    private double ceRsiMin = 50d;
    private double ceRsiMax = 75d;
    private double peRsiMin = 25d;
    private double peRsiMax = 50d;
    private boolean requireRsiDirection = false;
    private boolean requireCandleColour = true;
    private double bankniftyAdxThreshold = 18d;
    private double defaultAdxThreshold = 20d;

    @PostConstruct
    public void validate() {
        validateRange("CE RSI", ceRsiMin, ceRsiMax);
        validateRange("PE RSI", peRsiMin, peRsiMax);
        if (!inBounds(bankniftyAdxThreshold) || !inBounds(defaultAdxThreshold)) {
            throw new IllegalArgumentException("Supertrend ADX thresholds must be finite and between 0 and 100");
        }
    }

    private void validateRange(String label, double min, double max) {
        if (!inBounds(min) || !inBounds(max) || min >= max) {
            throw new IllegalArgumentException(label + " bounds must satisfy 0 <= min < max <= 100");
        }
    }

    private boolean inBounds(double value) {
        return Double.isFinite(value) && value >= 0d && value <= 100d;
    }
}
