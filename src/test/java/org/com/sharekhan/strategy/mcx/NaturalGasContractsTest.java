package org.com.sharekhan.strategy.mcx;

import org.junit.jupiter.api.Test;
import org.com.sharekhan.entity.ScriptMasterEntity;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NaturalGasContractsTest {
    static ScriptMasterEntity row(int code,String type,String side,String expiry,double strike){return ScriptMasterEntity.builder().scripCode(code).exchange("MX").tradingSymbol("NATURALGAS").instrumentType(type).optionType(side).expiry(expiry).strikePrice(strike).lotSize(1250).tickSize(.05).build();}
    @Test void mapsOptionToOwnDeliveryMonthFuture(){var f=row(1,"FUTC","XX","24/11/2026",0);var o=row(2,"OPTC","CE","20/11/2026",300);var oct=row(3,"FUTC","XX","27/10/2026",0);assertEquals(f,NaturalGasContracts.resolve(List.of(o,oct,f),LocalDate.of(2026,10,26)).future());}
    @Test void skipsExpiryDayOptions(){var oct=row(2,"OPTC","CE","26/10/2026",250);var nov=row(3,"OPTC","PE","20/11/2026",300);assertEquals(LocalDate.of(2026,11,20),NaturalGasContracts.resolve(List.of(oct,nov,row(1,"FUTC",null,"24/11/2026",0)),LocalDate.of(2026,10,26)).optionExpiry());}
    @Test void refusesAmbiguousUnderlying(){assertThrows(IllegalArgumentException.class,()->NaturalGasContracts.resolve(List.of(row(1,"FUTC",null,"24/11/2026",0),row(2,"FUTC",null,"25/11/2026",0),row(3,"OPTC","CE","20/11/2026",300)),LocalDate.of(2026,10,26)));}
    @Test void atmUsesFuturesPriceAndListedStrikesWithLowerTie(){var a=row(1,"OPTC","CE","20/11/2026",300);var b=row(2,"OPTC","CE","20/11/2026",305);assertEquals(a,NaturalGasContracts.atm(List.of(a,b),LocalDate.of(2026,11,20),"CE",302.5));assertEquals(b,NaturalGasContracts.atm(List.of(a,b),LocalDate.of(2026,11,20),"CE",303));}
    @Test void rejectsDuplicateExactOption(){var a=row(1,"OPTC","PE","20/11/2026",300);var b=row(2,"OPTC","PE","20/11/2026",300);assertThrows(IllegalArgumentException.class,()->NaturalGasContracts.atm(List.of(a,b),LocalDate.of(2026,11,20),"PE",300));}
    @Test void excludesNseAndMini(){var a=row(1,"OPTC","CE","20/11/2026",300);a.setExchange("NF");var b=row(2,"OPTC","CE","20/11/2026",300);b.setTradingSymbol("NATGASMINI");assertThrows(IllegalArgumentException.class,()->NaturalGasContracts.atm(List.of(a,b),LocalDate.of(2026,11,20),"CE",300));}
}
