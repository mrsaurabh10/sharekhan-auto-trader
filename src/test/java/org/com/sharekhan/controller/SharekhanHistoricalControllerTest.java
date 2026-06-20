package org.com.sharekhan.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.repository.ScriptMasterRepository;
import org.com.sharekhan.service.ScriptMasterService;
import org.com.sharekhan.service.SharekhanHistoricalService;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SharekhanHistoricalControllerTest {

    @Test
    void returnsHistoricalCandlesForScripCode() throws Exception {
        SharekhanHistoricalService historicalService = mock(SharekhanHistoricalService.class);
        ScriptMasterRepository scriptMasterRepository = mock(ScriptMasterRepository.class);
        ScriptMasterService scriptMasterService = mock(ScriptMasterService.class);
        MockMvc mockMvc = mockMvc(new SharekhanHistoricalController(
                historicalService, scriptMasterRepository, scriptMasterService));

        ScriptMasterEntity script = ScriptMasterEntity.builder()
                .scripCode(12345)
                .tradingSymbol("NIFTY")
                .exchange("NC")
                .build();
        when(scriptMasterRepository.findByScripCode(12345)).thenReturn(script);
        when(historicalService.getHistoricalCandles(
                eq(12345),
                eq("5minute"),
                eq(LocalDate.of(2026, 6, 1)),
                eq(LocalDate.of(2026, 6, 20))))
                .thenReturn(List.of(
                        new SharekhanHistoricalService.HistoricalCandle(
                                LocalDate.of(2026, 6, 20),
                                LocalTime.of(9, 20),
                                100.0,
                                105.0,
                                99.5,
                                103.25)
                ));

        mockMvc.perform(get("/api/sharekhan/historical/candles")
                        .param("scripCode", "12345")
                        .param("interval", "5minute")
                        .param("from", "2026-06-01")
                        .param("to", "2026-06-20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("success")))
                .andExpect(jsonPath("$.scripCode", is(12345)))
                .andExpect(jsonPath("$.tradingSymbol", is("NIFTY")))
                .andExpect(jsonPath("$.interval", is("5minute")))
                .andExpect(jsonPath("$.from", is("2026-06-01")))
                .andExpect(jsonPath("$.to", is("2026-06-20")))
                .andExpect(jsonPath("$.count", is(1)))
                .andExpect(jsonPath("$.candles[0].date", is("2026-06-20")))
                .andExpect(jsonPath("$.candles[0].time", is("09:20:00")))
                .andExpect(jsonPath("$.candles[0].open", is(100.0)))
                .andExpect(jsonPath("$.candles[0].close", is(103.25)));
    }

    @Test
    void resolvesScriptFromExchangeAndInstrument() throws Exception {
        SharekhanHistoricalService historicalService = mock(SharekhanHistoricalService.class);
        ScriptMasterRepository scriptMasterRepository = mock(ScriptMasterRepository.class);
        ScriptMasterService scriptMasterService = mock(ScriptMasterService.class);
        MockMvc mockMvc = mockMvc(new SharekhanHistoricalController(
                historicalService, scriptMasterRepository, scriptMasterService));

        ScriptMasterEntity script = ScriptMasterEntity.builder()
                .scripCode(54321)
                .tradingSymbol("RELIANCE")
                .exchange("NC")
                .build();
        when(scriptMasterRepository.findByExchangeIgnoreCaseAndTradingSymbolIgnoreCase("NC", "RELIANCE"))
                .thenReturn(List.of(script));
        when(historicalService.getHistoricalCandles(eq(54321), eq("15minute"), eq(null), eq(null)))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/sharekhan/historical/candles")
                        .param("exchange", "nc")
                        .param("instrument", "RELIANCE")
                        .param("interval", "15minute"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scripCode", is(54321)))
                .andExpect(jsonPath("$.interval", is("15minute")))
                .andExpect(jsonPath("$.count", is(0)));
    }

    @Test
    void resolvesOptionScriptWhenOptionFieldsAreProvided() throws Exception {
        SharekhanHistoricalService historicalService = mock(SharekhanHistoricalService.class);
        ScriptMasterRepository scriptMasterRepository = mock(ScriptMasterRepository.class);
        ScriptMasterService scriptMasterService = mock(ScriptMasterService.class);
        MockMvc mockMvc = mockMvc(new SharekhanHistoricalController(
                historicalService, scriptMasterRepository, scriptMasterService));

        ScriptMasterEntity option = ScriptMasterEntity.builder()
                .scripCode(11111)
                .tradingSymbol("NIFTY")
                .exchange("NF")
                .strikePrice(23500.0)
                .optionType("CE")
                .expiry("25/06/2026")
                .build();
        when(scriptMasterService.findOption("NF", "NIFTY", 23500.0, "CE", "25/06/2026"))
                .thenReturn(Optional.of(option));
        when(historicalService.getHistoricalCandles(eq(11111), eq("5minute"), eq(null), eq(null)))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/sharekhan/historical/candles")
                        .param("exchange", "NF")
                        .param("instrument", "NIFTY")
                        .param("strikePrice", "23500")
                        .param("optionType", "CE")
                        .param("expiry", "25/06/2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scripCode", is(11111)))
                .andExpect(jsonPath("$.interval", is("5minute")));

        verify(scriptMasterService).findOption("NF", "NIFTY", 23500.0, "CE", "25/06/2026");
    }

    @Test
    void rejectsPartialDateRange() throws Exception {
        SharekhanHistoricalService historicalService = mock(SharekhanHistoricalService.class);
        ScriptMasterRepository scriptMasterRepository = mock(ScriptMasterRepository.class);
        ScriptMasterService scriptMasterService = mock(ScriptMasterService.class);
        MockMvc mockMvc = mockMvc(new SharekhanHistoricalController(
                historicalService, scriptMasterRepository, scriptMasterService));

        mockMvc.perform(get("/api/sharekhan/historical/candles")
                        .param("scripCode", "12345")
                        .param("from", "2026-06-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is("error")));
    }

    @Test
    void rejectsUnsafeIntervalSegment() throws Exception {
        SharekhanHistoricalService historicalService = mock(SharekhanHistoricalService.class);
        ScriptMasterRepository scriptMasterRepository = mock(ScriptMasterRepository.class);
        ScriptMasterService scriptMasterService = mock(ScriptMasterService.class);
        MockMvc mockMvc = mockMvc(new SharekhanHistoricalController(
                historicalService, scriptMasterRepository, scriptMasterService));

        mockMvc.perform(get("/api/sharekhan/historical/candles")
                        .param("scripCode", "12345")
                        .param("interval", "5minute?from=2026-01-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is("error")));
    }

    @Test
    void rejectsPartialOptionLookup() throws Exception {
        SharekhanHistoricalService historicalService = mock(SharekhanHistoricalService.class);
        ScriptMasterRepository scriptMasterRepository = mock(ScriptMasterRepository.class);
        ScriptMasterService scriptMasterService = mock(ScriptMasterService.class);
        MockMvc mockMvc = mockMvc(new SharekhanHistoricalController(
                historicalService, scriptMasterRepository, scriptMasterService));

        mockMvc.perform(get("/api/sharekhan/historical/candles")
                        .param("exchange", "NF")
                        .param("instrument", "NIFTY")
                        .param("strikePrice", "23500"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is("error")));
    }

    @Test
    void returnsNotFoundWhenScriptCannotBeResolved() throws Exception {
        SharekhanHistoricalService historicalService = mock(SharekhanHistoricalService.class);
        ScriptMasterRepository scriptMasterRepository = mock(ScriptMasterRepository.class);
        ScriptMasterService scriptMasterService = mock(ScriptMasterService.class);
        MockMvc mockMvc = mockMvc(new SharekhanHistoricalController(
                historicalService, scriptMasterRepository, scriptMasterService));

        mockMvc.perform(get("/api/sharekhan/historical/candles")
                        .param("scripCode", "99999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status", is("error")));
    }

    private MockMvc mockMvc(SharekhanHistoricalController controller) {
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }
}
