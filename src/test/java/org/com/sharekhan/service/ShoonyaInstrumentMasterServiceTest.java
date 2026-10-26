package org.com.sharekhan.service;

import org.junit.jupiter.api.Test;
import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.entity.ShoonyaInstrumentEntity;
import org.com.sharekhan.repository.ShoonyaInstrumentRepository;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShoonyaInstrumentMasterServiceTest {
    @Test
    void resolvesSensexSpotWithoutAnEquityMasterRowOrLocalCodeAsProviderToken() {
        ShoonyaInstrumentRepository repository = mock(ShoonyaInstrumentRepository.class);
        var service = new ShoonyaInstrumentMasterService(repository, mock(ShoonyaInstrumentMasterWriter.class));
        for (String exchange : List.of("BC", "BSE")) {
            var script = ScriptMasterEntity.builder().scripCode(999901).exchange(exchange)
                    .tradingSymbol(" sensex ").optionType("").build();
            var resolved = service.resolveScript(script).orElseThrow();
            assertThat(resolved.getExchange()).isEqualTo("BSE");
            assertThat(resolved.getToken()).isEqualTo("1");
            assertThat(resolved.getTradingSymbol()).isEqualTo("SENSEX");
        }
        org.mockito.Mockito.verifyNoInteractions(repository);
    }

    @Test
    void sensexMappingDoesNotApplyToOtherIndicesEtfsOrExchanges() {
        var repository = mock(ShoonyaInstrumentRepository.class);
        var service = new ShoonyaInstrumentMasterService(repository, mock(ShoonyaInstrumentMasterWriter.class));
        for (String symbol : List.of("SENSEX50", "SENSEXIETF", "BANKEX")) {
            assertThat(service.resolveScript(ScriptMasterEntity.builder().exchange("BC")
                    .tradingSymbol(symbol).build())).isEmpty();
        }
        assertThat(service.resolveScript(ScriptMasterEntity.builder().exchange("NC")
                .tradingSymbol("SENSEX").build())).isEmpty();
    }

    @Test
    void resolvesNiftyAndBankNiftyByTheirIndexTokensInsteadOfEquityLabels() {
        ShoonyaInstrumentRepository repository = mock(ShoonyaInstrumentRepository.class);
        ShoonyaInstrumentMasterService service = new ShoonyaInstrumentMasterService(repository, mock(ShoonyaInstrumentMasterWriter.class));
        for (String symbol : List.of("NIFTY", "BANKNIFTY")) {
            String token = symbol.equals("NIFTY") ? "26000" : "26009";
            ShoonyaInstrumentEntity index = ShoonyaInstrumentEntity.builder().exchange("NSE").token(token)
                    .tradingSymbol(symbol.equals("NIFTY") ? "NIFTY INDEX" : "NIFTY BANK").build();
            when(repository.findFirstByExchangeIgnoreCaseAndToken("NSE", token)).thenReturn(java.util.Optional.of(index));
            ScriptMasterEntity script = ScriptMasterEntity.builder().exchange("NC").tradingSymbol(symbol).optionType("").build();
            assertThat(service.resolveScript(script)).containsSame(index);
        }
    }
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

    @Test
    void ignoresDuplicateTradingSymbolsToKeepTheMasterReplaceable() throws Exception {
        String master = "Exchange,Token,LotSize,TickSize,Symbol,TradingSymbol,Expiry,Instrument,OptionType,StrikePrice\n"
                + "BSE,12,1,0.01,TEST,12:,,EQ,,0\n"
                + "BSE,13,1,0.01,TEST,12:,,EQ,,0\n";

        List<?> instruments = ShoonyaInstrumentMasterService.parse("BSE", zip(master));

        assertThat(instruments).hasSize(1);
        var instrument = (org.com.sharekhan.entity.ShoonyaInstrumentEntity) instruments.get(0);
        assertThat(instrument.getInstrumentKey()).isEqualTo("BSE:12:");
        assertThat(instrument.getToken()).isEqualTo("12");
    }

    @Test
    void resolvesSharekhanSensexOptionAgainstShoonyaBsxoptUnderlying() {
        ShoonyaInstrumentRepository repository = mock(ShoonyaInstrumentRepository.class);
        ShoonyaInstrumentMasterService service = new ShoonyaInstrumentMasterService(repository, mock(ShoonyaInstrumentMasterWriter.class));
        ScriptMasterEntity script = ScriptMasterEntity.builder()
                .tradingSymbol("SENSEX")
                .exchange("BF")
                .optionType("PE")
                .strikePrice(76500d)
                .expiry("03/09/2026")
                .build();
        ShoonyaInstrumentEntity instrument = ShoonyaInstrumentEntity.builder().token("859073").build();
        when(repository.findFirstByExchangeIgnoreCaseAndSymbolIgnoreCaseAndExpiryIgnoreCaseAndOptionTypeIgnoreCaseAndStrikePrice(
                "BFO", "BSXOPT", "03-SEP-2026", "PE", 76500d)).thenReturn(java.util.Optional.of(instrument));

        assertThat(service.resolveOption(script)).containsSame(instrument);
        verify(repository).findFirstByExchangeIgnoreCaseAndSymbolIgnoreCaseAndExpiryIgnoreCaseAndOptionTypeIgnoreCaseAndStrikePrice(
                eq("BFO"), eq("BSXOPT"), eq("03-SEP-2026"), eq("PE"), eq(76500d));
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
    @Test void resolvesExactMcxFutureAndChecksExpiryLotAndTickInsteadOfAssumingTokenEquality() throws Exception {
        var repo=mock(ShoonyaInstrumentRepository.class);var service=org.mockito.Mockito.spy(new ShoonyaInstrumentMasterService(repo,mock(ShoonyaInstrumentMasterWriter.class)));
        var now=java.time.LocalDateTime.of(2026,10,26,19,0);
        var future=ScriptMasterEntity.builder().exchange("MX").tradingSymbol("NATURALGAS").instrumentType("FS").expiry("24/11/2026").lotSize(1250).tickSize(.1).build();
        var row=ShoonyaInstrumentEntity.builder().exchange("MCX").token("provider-token").tradingSymbol("NATURALGAS24NOV26").symbol("NATURALGAS")
            .instrument("FUTCOM").expiry("24-NOV-2026").lotSize(1250).tickSize(.1).fetchedAt(now).build();
        when(repo.findByExchangeIgnoreCaseAndTradingSymbolIgnoreCase("MCX","NATURALGAS24NOV26")).thenReturn(java.util.Optional.of(row));
        assertThat(service.resolveNaturalGasFuture(future,now).getToken()).isEqualTo("provider-token");
        row.setLotSize(250);org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,()->service.resolveNaturalGasFuture(future,now));
        row.setLotSize(1250);row.setFetchedAt(now.minusDays(1));org.mockito.Mockito.doReturn(1).when(service).refresh("MCX");
        service.resolveNaturalGasFuture(future,now);verify(service).refresh("MCX");
    }

}
