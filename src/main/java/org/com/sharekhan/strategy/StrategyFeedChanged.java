package org.com.sharekhan.strategy;

/** Published inside scheduling transactions; feed ownership is updated only after commit. */
public record StrategyFeedChanged(String templateId) { }
