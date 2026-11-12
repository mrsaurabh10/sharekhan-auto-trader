package org.com.sharekhan.strategy.adxdmi;

import lombok.RequiredArgsConstructor;
import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.service.ShoonyaInstrumentMasterService;
import org.springframework.stereotype.Service;
import java.time.*;
import java.time.format.*;
import java.util.*;

/** Read-only exact-contract metadata fallback; never guesses a tick or updates the script master. */
@Service @RequiredArgsConstructor
public class AdxDmiContractMetadata {
    private final ShoonyaInstrumentMasterService provider;
    public double optionTick(ScriptMasterEntity option,LocalDateTime now) {
        if(option.getLotSize()==null || option.getLotSize()<=0)throw new IllegalStateException("Option lot metadata unavailable");
        if(option.getTickSize()!=null) {
            if(!Double.isFinite(option.getTickSize()) || option.getTickSize()<=0)throw new IllegalStateException("Invalid option tick metadata");
            return option.getTickSize();
        }
        var mapped=provider.resolveOption(option).orElseThrow(()->new IllegalStateException("Exact option tick metadata unavailable"));
        String exchange=switch(Objects.toString(option.getExchange(),"").toUpperCase(Locale.ROOT)) {
            case "NF","NFO"->"NFO"; case "BF","BFO"->"BFO"; default->"";
        };
        var expiry=expiry(option.getExpiry());
        if(!ShoonyaInstrumentMasterService.sameOptionUnderlying(option,mapped) || exchange.isBlank() || !exchange.equalsIgnoreCase(mapped.getExchange()) || expiry==null || !expiry.equals(expiry(mapped.getExpiry()))
                || !Objects.toString(option.getOptionType(),"").equalsIgnoreCase(mapped.getOptionType())
                || !Objects.equals(option.getStrikePrice(),mapped.getStrikePrice()) || !Objects.equals(option.getLotSize(),mapped.getLotSize())
                || mapped.getToken()==null || mapped.getToken().isBlank() || mapped.getTradingSymbol()==null || mapped.getTradingSymbol().isBlank()
                || mapped.getInstrument()==null || !mapped.getInstrument().toUpperCase(Locale.ROOT).startsWith("OPT")
                || mapped.getFetchedAt()==null || !mapped.getFetchedAt().toLocalDate().equals(now.toLocalDate()) || mapped.getFetchedAt().isAfter(now)
                || mapped.getTickSize()==null || !Double.isFinite(mapped.getTickSize()) || mapped.getTickSize()<=0)
            throw new IllegalStateException("Exact provider option metadata missing, stale or mismatched");
        return mapped.getTickSize();
    }
    private static LocalDate expiry(String text) {
        if(text==null)return null;
        for(var f:List.of(DateTimeFormatter.ISO_LOCAL_DATE,DateTimeFormatter.ofPattern("dd/MM/uuuu"),
                new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("dd-MMM-uuuu").toFormatter(Locale.ENGLISH)))
            try{return LocalDate.parse(text.trim(),f);}catch(DateTimeParseException ignored){ }
        return null;
    }
}
