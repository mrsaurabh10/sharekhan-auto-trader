package org.com.sharekhan.service;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class NseMarketCalendarTest {

    @Test
    void rejectsWeekendsAndConfiguredHolidays() {
        NseMarketCalendar calendar = new NseMarketCalendar();
        ReflectionTestUtils.setField(calendar, "configuredHolidays", "2026-01-26,2026-03-03");

        assertThat(calendar.isTradingDay(LocalDate.of(2026, 7, 18))).isFalse();
        assertThat(calendar.isTradingDay(LocalDate.of(2026, 1, 26))).isFalse();
        assertThat(calendar.isTradingDay(LocalDate.of(2026, 7, 17))).isTrue();
    }
}
