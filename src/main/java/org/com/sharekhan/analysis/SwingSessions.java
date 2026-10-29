package org.com.sharekhan.analysis;

import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;

/** Research-only calendar. Fail closed beyond the verified year; does not alter strategy calendars. */
@Component
public class SwingSessions {
    // NSE cash calendar: https://www.nseindia.com/resources/exchange-communication-holidays
    private static final Set<LocalDate> HOLIDAYS = Set.of("2026-01-15","2026-01-26","2026-03-03","2026-03-26",
            "2026-03-31","2026-04-03","2026-04-14","2026-05-01","2026-05-28","2026-06-26",
            "2026-09-14","2026-10-02","2026-10-20","2026-11-10","2026-11-24","2026-12-25").stream()
            .map(LocalDate::parse).collect(java.util.stream.Collectors.toUnmodifiableSet());
    public boolean supported(LocalDate day) { return day != null && day.getYear() == 2026; }
    public boolean special(LocalDate day) { return LocalDate.of(2026,11,8).equals(day); }
    public boolean regular(LocalDate day) {
        return supported(day) && day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY && !HOLIDAYS.contains(day);
    }
    public LocalDate next(LocalDate day) {
        for (LocalDate d = day.plusDays(1); supported(d); d = d.plusDays(1)) {if(special(d))return null;if (regular(d)) return d;}
        return null;
    }
    public LocalDate expected(LocalDateTime now) {
        LocalDate d = now.toLocalDate();
        if (!supported(d)) return null;
        if (now.toLocalTime().isBefore(LocalTime.of(18,0))) d = d.minusDays(1);
        while (supported(d) && !regular(d)) d = d.minusDays(1);
        return supported(d) ? d : null;
    }
}
