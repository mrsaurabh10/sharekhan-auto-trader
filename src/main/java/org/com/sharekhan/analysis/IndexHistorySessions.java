package org.com.sharekhan.analysis;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.com.sharekhan.service.NseMarketCalendar;
import java.time.*;
import java.util.*;

/** Index archive calendar; future weekday sessions use the existing configured NSE holiday calendar. */
@Component
public class IndexHistorySessions {
    public record Window(LocalDate day,LocalTime start,LocalTime end) {
        public int minutes(){return Math.toIntExact(Duration.between(start,end).toMinutes());}
        public boolean special(){return !start.equals(LocalTime.of(9,15))||!end.equals(LocalTime.of(15,30));}
    }
    private final SwingSessions verified;
    private final NseMarketCalendar calendar;
    private final Map<LocalDate,Window> special=new HashMap<>();
    public IndexHistorySessions(SwingSessions verified,NseMarketCalendar calendar,@Value("${app.index-history.special-sessions:}") String configured) {
        this.verified=verified;this.calendar=calendar;
        // NSE/CMTR/76941, 19 Oct 2026: normal non-CAS session14:20–15:30.
        var diwali=LocalDate.of(2026,11,8);special.put(diwali,new Window(diwali,LocalTime.of(14,20),LocalTime.of(15,30)));
        if(configured!=null&&!configured.isBlank())for(String spec:configured.split(",")) {
            var parts=spec.trim().split("=");var times=parts[1].split("-");var day=LocalDate.parse(parts[0]);
            var w=new Window(day,LocalTime.parse(times[0]),LocalTime.parse(times[1]));
            if(w.minutes()<=0||w.minutes()>600||w.minutes()%5!=0||w.start().getSecond()!=0||w.end().getSecond()!=0||w.start().getMinute()%5!=0)throw new IllegalArgumentException("Invalid index session window");special.put(day,w);
        }
    }
    public Window window(LocalDate day){
        if(special.containsKey(day))return special.get(day);
        boolean open=verified.supported(day)?verified.regular(day):calendar.isTradingDay(day);
        return open?new Window(day,LocalTime.of(9,15),LocalTime.of(15,30)):null;
    }
    public LocalDate through(LocalDateTime now){return now.toLocalTime().isBefore(LocalTime.of(19,0))?now.toLocalDate().minusDays(1):now.toLocalDate();}
}
