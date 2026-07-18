package org.com.sharekhan.strategy;

import org.springframework.stereotype.Component;
import org.com.sharekhan.service.TelegramNotificationService;

@Component
public class ManualFnoVwapReclaimPeStrategy extends AbstractManualFnoVwapReclaimBaseBreakoutStrategy {

    public static final String TEMPLATE_ID = "FNO_VWAP_RECLAIM_BASE_PE";

    public ManualFnoVwapReclaimPeStrategy(StrategySupport support,
                                           Fno925EntryQualificationService qualificationService,
                                           TelegramNotificationService telegramNotificationService) {
        super(new StrategyMetadata(
                        TEMPLATE_ID,
                        "F&O VWAP Reclaim / ORB (PE)",
                        "Enter a comma-separated list of F&O underlyings. Monitors morning ORB and VWAP-reclaim base-break PE setups.",
                        "PE"),
                support,
                qualificationService,
                telegramNotificationService);
    }
}
