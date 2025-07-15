package org.com.sharekhan.monitoring;

import org.com.sharekhan.entity.TriggeredTradeSetupEntity;

public record OrderPlacedEvent(TriggeredTradeSetupEntity trade) {}