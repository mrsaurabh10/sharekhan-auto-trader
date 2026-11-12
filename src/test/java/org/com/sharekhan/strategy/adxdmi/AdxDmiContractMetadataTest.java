package org.com.sharekhan.strategy.adxdmi;
import org.com.sharekhan.entity.*;
import org.com.sharekhan.service.ShoonyaInstrumentMasterService;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdxDmiContractMetadataTest {
    static final LocalDateTime NOW=LocalDateTime.of(2026,11,12,11,59);
    ScriptMasterEntity local(){return ScriptMasterEntity.builder().scripCode(1165549).tradingSymbol("SENSEX").exchange("BF")
            .expiry("12/11/2026").optionType("PE").strikePrice(81700d).lotSize(20).build();}
    ShoonyaInstrumentEntity exact(){return ShoonyaInstrumentEntity.builder().token("1165549").exchange("BFO").symbol("BSXOPT")
            .tradingSymbol("SENSEX26N1281700PE").instrument("OPTIDX").expiry("12-NOV-2026").optionType("PE")
            .strikePrice(81700d).lotSize(20).tickSize(.05).fetchedAt(NOW.withHour(8).withMinute(15)).build();}
    @Test void resolvesMissingTickFromExactProviderContractWithoutEditingLocalMaster(){
        var provider=mock(ShoonyaInstrumentMasterService.class);var option=local();when(provider.resolveOption(option)).thenReturn(Optional.of(exact()));
        assertEquals(.05,new AdxDmiContractMetadata(provider).optionTick(option,NOW));assertNull(option.getTickSize());
        option.setTickSize(.1);assertEquals(.1,new AdxDmiContractMetadata(provider).optionTick(option,NOW));
    }
    @Test void mismatchedStaleOrInvalidProviderMetadataNeverSuppliesTick(){
        for(int n=0;n<9;n++){
            var provider=mock(ShoonyaInstrumentMasterService.class);var option=local();var row=exact();
            switch(n){case 0->row.setStrikePrice(81800d);case 1->row.setOptionType("CE");case 2->row.setExchange("NFO");
                case 3->row.setExpiry("19-NOV-2026");case 4->row.setLotSize(10);case 5->row.setFetchedAt(NOW.minusDays(1));
                case 6->row.setTickSize(Double.NaN);case 7->row.setToken("");case 8->row.setSymbol("OTHER");}
            when(provider.resolveOption(option)).thenReturn(Optional.of(row));
            assertThrows(IllegalStateException.class,()->new AdxDmiContractMetadata(provider).optionTick(option,NOW));
        }
    }
    @Test void invalidLocalTickIsNotSilentlyReplacedAndMissingLotStillWaits(){
        var provider=mock(ShoonyaInstrumentMasterService.class);var option=local();option.setTickSize(0d);
        assertThrows(IllegalStateException.class,()->new AdxDmiContractMetadata(provider).optionTick(option,NOW));
        option.setTickSize(null);option.setLotSize(null);
        assertThrows(IllegalStateException.class,()->new AdxDmiContractMetadata(provider).optionTick(option,NOW));verifyNoInteractions(provider);
    }
}
