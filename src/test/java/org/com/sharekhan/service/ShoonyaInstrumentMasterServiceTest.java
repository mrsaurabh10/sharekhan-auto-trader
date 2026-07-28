package org.com.sharekhan.service;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class ShoonyaInstrumentMasterServiceTest {
    @Test
    void parsesShoonyaNfoMasterIntoResolvableInstrumentRows() throws Exception {
        String master = "Exchange,Token,LotSize,TickSize,Symbol,TradingSymbol,Expiry,Instrument,OptionType,StrikePrice\n"
                + "NFO,12345,75,0.05,NIFTY,NIFTY30JUL26C25000,30-JUL-2026,OPTIDX,CE,25000\n";
        List<?> instruments = ShoonyaInstrumentMasterService.parse("NFO", zip(master));

        assertThat(instruments).hasSize(1);
        var instrument = (org.com.sharekhan.entity.ShoonyaInstrumentEntity) instruments.get(0);
        assertThat(instrument.getInstrumentKey()).isEqualTo("NFO:NIFTY30JUL26C25000");
        assertThat(instrument.getToken()).isEqualTo("12345");
        assertThat(instrument.getOptionType()).isEqualTo("CE");
        assertThat(instrument.getStrikePrice()).isEqualTo(25000d);
    }

    private ByteArrayInputStream zip(String content) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream output = new ZipOutputStream(bytes)) {
            output.putNextEntry(new ZipEntry("NFO_symbols.txt"));
            output.write(content.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return new ByteArrayInputStream(bytes.toByteArray());
    }
}
