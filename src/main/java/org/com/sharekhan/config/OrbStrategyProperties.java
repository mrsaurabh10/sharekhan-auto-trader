package org.com.sharekhan.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data @Component @ConfigurationProperties(prefix="app.strategy.orb")
public class OrbStrategyProperties {
    private int maxSignalDelaySeconds=120;
    private int maxSpotAgeSeconds=30;
    private double maxEntryDeviationRisk=0.25;
    @PostConstruct public void validate() {
        if (maxSignalDelaySeconds < 1 || maxSignalDelaySeconds >= 300 || maxSpotAgeSeconds < 1
                || !Double.isFinite(maxEntryDeviationRisk) || maxEntryDeviationRisk <= 0 || maxEntryDeviationRisk > 1)
            throw new IllegalArgumentException("ORB signal delay must be 1–299 seconds, spot age positive, entry deviation risk in (0,1]");
    }
}
