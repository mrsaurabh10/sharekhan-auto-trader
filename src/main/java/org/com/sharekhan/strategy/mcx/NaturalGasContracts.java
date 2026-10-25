package org.com.sharekhan.strategy.mcx;

import org.com.sharekhan.entity.ScriptMasterEntity;
import java.time.*;
import java.time.format.*;
import java.util.*;

public final class NaturalGasContracts {
    private static final DateTimeFormatter FORMAT=DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(ResolverStyle.STRICT);
    public record Pair(ScriptMasterEntity future, LocalDate optionExpiry) { }
    private NaturalGasContracts() { }
    public static LocalDate expiry(ScriptMasterEntity row) {
        try {return LocalDate.parse(row.getExpiry(),FORMAT);}catch(RuntimeException invalid){return null;}
    }
    private static boolean base(ScriptMasterEntity r){return r!=null && "MX".equalsIgnoreCase(r.getExchange()) && "NATURALGAS".equalsIgnoreCase(r.getTradingSymbol()) && expiry(r)!=null;}
    public static Pair resolve(List<ScriptMasterEntity> rows,LocalDate today) {
        var expiry=rows.stream().filter(NaturalGasContracts::base).filter(r->Set.of("CE","PE").contains(Objects.toString(r.getOptionType(),"")))
                .map(NaturalGasContracts::expiry).filter(d->d.isAfter(today)).min(LocalDate::compareTo)
                .orElseThrow(()->new IllegalArgumentException("No non-expiry-day MCX NATURALGAS options available"));
        var futures=rows.stream().filter(NaturalGasContracts::base)
                .filter(r->r.getInstrumentType()!=null && r.getInstrumentType().toUpperCase(Locale.ROOT).startsWith("F"))
                .filter(r->r.getStrikePrice()==null || r.getStrikePrice()==0)
                .filter(r->!Set.of("CE","PE").contains(Objects.toString(r.getOptionType(),"")))
                .filter(r->YearMonth.from(expiry(r)).equals(YearMonth.from(expiry)) && !expiry(r).isBefore(expiry)).toList();
        if(futures.size()!=1) throw new IllegalArgumentException("MCX option underlying futures mapping is missing or ambiguous");
        return new Pair(futures.get(0),expiry);
    }
    public static ScriptMasterEntity atm(List<ScriptMasterEntity> rows,LocalDate expiry,String side,double price) {
        if(!Double.isFinite(price)||price<=0) throw new IllegalArgumentException("Invalid futures price");
        var eligible=rows.stream().filter(NaturalGasContracts::base).filter(r->expiry.equals(expiry(r)) && side.equals(r.getOptionType()))
                .filter(r->r.getStrikePrice()!=null && Double.isFinite(r.getStrikePrice()) && r.getStrikePrice()>0)
                .sorted(Comparator.<ScriptMasterEntity>comparingDouble(r->Math.abs(r.getStrikePrice()-price)).thenComparingDouble(ScriptMasterEntity::getStrikePrice)).toList();
        if(eligible.isEmpty()) throw new IllegalArgumentException("No listed MCX ATM "+side+" contract");
        var selected=eligible.get(0);
        if(eligible.stream().filter(r->r.getStrikePrice().equals(selected.getStrikePrice())).count()!=1)
            throw new IllegalArgumentException("Ambiguous MCX ATM contract");
        if(selected.getLotSize()==null||selected.getLotSize()<=0||selected.getTickSize()==null||!Double.isFinite(selected.getTickSize())||selected.getTickSize()<=0)
            throw new IllegalArgumentException("MCX lot size/tick metadata unavailable");
        return selected;
    }
}
