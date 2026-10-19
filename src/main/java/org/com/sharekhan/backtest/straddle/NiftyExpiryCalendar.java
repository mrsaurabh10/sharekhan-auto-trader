package org.com.sharekhan.backtest.straddle;

import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

/** Nominal schedule only. Holiday shifts require explicit, verified expiry dates. */
public final class NiftyExpiryCalendar {
    private NiftyExpiryCalendar() { }
    public static List<LocalDate> nominal(LocalDate from,LocalDate to) {
        var dates=new ArrayList<LocalDate>();
        for(LocalDate day=from;!day.isAfter(to);day=day.plusDays(1)) {
            boolean weekly=!day.isBefore(LocalDate.of(2019,2,11));
            DayOfWeek weekday=day.isBefore(LocalDate.of(2025,9,1))?DayOfWeek.THURSDAY:DayOfWeek.TUESDAY;
            if(day.getDayOfWeek()==weekday && (weekly || day.equals(day.with(TemporalAdjusters.lastInMonth(weekday))))) dates.add(day);
        }
        return List.copyOf(dates);
    }
}
