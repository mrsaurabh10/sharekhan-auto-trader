package org.com.sharekhan.strategy.mcx;

import org.springframework.stereotype.Component;
import java.math.BigDecimal;

/** Explicit Simulator trial. Live brokers always retain their original policy. */
@Component
public class NaturalGasScalpPolicy {
    public static final int MAX_ATTEMPTS=5, COOLDOWN_MINUTES=6, BREAKOUT_BARS=10;
    public static final BigDecimal DAILY_LIMIT=new BigDecimal("2500.00");
    /** Conservative paper-trade allowance, not a broker-reconciled MCX charge calculation. */
    public static final double COST_RESERVE=100;
    public static final double STOP_FRACTION=.10, TARGET_FRACTION=.15;
    public boolean forBroker(String broker){return "SIMULATOR".equalsIgnoreCase(broker);}
    public boolean enabled(){return true;}
}
