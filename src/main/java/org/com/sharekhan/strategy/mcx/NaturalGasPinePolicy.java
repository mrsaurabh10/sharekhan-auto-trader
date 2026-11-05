package org.com.sharekhan.strategy.mcx;

import java.time.*;
import java.util.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Settings belong only to the Pine-derived strategy, never the existing NG strategies. */
@Component @ConfigurationProperties(prefix="app.strategy.ng-pine") @Getter @Setter
public class NaturalGasPinePolicy implements org.springframework.beans.factory.InitializingBean {
    public enum EntryMode { CROSSOVER, PULLBACK }
    private EntryMode entryMode=EntryMode.CROSSOVER;
    private double stopAtr=1.5;
    private double rewardR=2.0;
    private int maxEntries=3;
    private LocalTime entryStart=LocalTime.of(18,30);
    private LocalTime entryCutoff=LocalTime.of(23,15);
    private LocalTime squareOff=LocalTime.of(23,25);
    private boolean exitOnInvalidation=true;
    private boolean avoidEia=true;
    private List<Blackout> blackouts=new ArrayList<>();
    @Getter @Setter public static class Blackout {
        private LocalDateTime start;
        private LocalDateTime end;
    }
    @Override public void afterPropertiesSet() {
        if(entryMode==null || !Double.isFinite(stopAtr) || stopAtr<=0 || !Double.isFinite(rewardR) || rewardR<=0
                || maxEntries<1 || maxEntries>20 || entryStart==null || entryCutoff==null || squareOff==null
                || entryStart.isBefore(NaturalGasSession.OPEN) || !entryStart.isBefore(entryCutoff)
                || !entryCutoff.isBefore(squareOff) || !squareOff.isBefore(NaturalGasSession.CLOSE)
                || entryStart.getMinute()%5!=0 || entryCutoff.getMinute()%5!=0 || squareOff.getMinute()%5!=0
                || entryStart.getSecond()!=0 || entryCutoff.getSecond()!=0 || squareOff.getSecond()!=0)
            throw new IllegalArgumentException("Invalid NG Pine risk/session settings; use aligned 5m boundaries before 23:30 IST");
        if(blackouts==null || blackouts.stream().anyMatch(b->b==null || b.start==null || b.end==null || !b.start.isBefore(b.end)))
            throw new IllegalArgumentException("NG Pine blackouts require ordered IST start/end timestamps");
    }
    public boolean blocked(LocalDateTime now) {
        var ny=now.atZone(ZoneId.of("Asia/Kolkata")).withZoneSameInstant(ZoneId.of("America/New_York"));
        boolean weekly=avoidEia && ny.getDayOfWeek()==DayOfWeek.THURSDAY
                && !ny.toLocalTime().isBefore(LocalTime.of(10,20)) && ny.toLocalTime().isBefore(LocalTime.of(10,45));
        return weekly || blackouts.stream().anyMatch(b->!now.isBefore(b.start) && now.isBefore(b.end));
    }
    public boolean entryAllowed(LocalDateTime now) {
        return NaturalGasSession.marketOpen(now) && !now.toLocalTime().isBefore(entryStart)
                && now.toLocalTime().isBefore(entryCutoff) && !blocked(now);
    }
}
