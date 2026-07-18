package org.com.sharekhan.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** Minimal market-session guard for scheduled intraday work. */
@Component
public class NseMarketCalendar {

    @Value("${app.market-data.nse-holidays:}")
    private String configuredHolidays;

    public boolean isTradingDay(LocalDate date) {
        if (date == null) {
            return false;
        }
        DayOfWeek dayOfWeek = date.getDayOfWeek();
        if (dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY) {
            return false;
        }
        return !holidays().contains(date);
    }

    private Set<LocalDate> holidays() {
        if (configuredHolidays == null || configuredHolidays.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(configuredHolidays.split("[,;\\s]+"))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(this::parseDate)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
    }

    private LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (Exception ignored) {
            return null;
        }
    }
}
