package org.com.sharekhan.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.com.sharekhan.ws.QuotePayloadParser.BidAsk;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class QuotePayloadParserTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void extractsDirectBidAsk() throws Exception {
        String json = "{\"bestBidPrice\": 102.5, \"bestAskPrice\": 103.0}";
        BidAsk bidAsk = QuotePayloadParser.extractBestBidAsk(MAPPER.readTree(json));

        assertEquals(102.5, bidAsk.bid());
        assertEquals(103.0, bidAsk.ask());
    }

    @Test
    void fallsBackToDepthArrays() throws Exception {
        String json = "{\"depth\": {\"buy\": [{\"price\": 98.25}], \"sell\": [{\"price\": 99.1}]}}";
        BidAsk bidAsk = QuotePayloadParser.extractBestBidAsk(MAPPER.readTree(json));

        assertEquals(98.25, bidAsk.bid());
        assertEquals(99.1, bidAsk.ask());
    }

    @Test
    void handlesNestedDepthWithNumericEntries() throws Exception {
        String json = "{\"depth\": {\"bid\": [97.45], \"ask\": [98.05]}}";
        BidAsk bidAsk = QuotePayloadParser.extractBestBidAsk(MAPPER.readTree(json));

        assertEquals(97.45, bidAsk.bid());
        assertEquals(98.05, bidAsk.ask());
    }

    @Test
    void returnsNullWhenNoBookInfo() throws Exception {
        BidAsk bidAsk = QuotePayloadParser.extractBestBidAsk(MAPPER.readTree("{}"));

        assertNull(bidAsk.bid());
        assertNull(bidAsk.ask());
    }

    @org.junit.jupiter.api.Test
    void exchangeProvenanceRequiresExplicitAndConsistentProviderFields() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        org.assertj.core.api.Assertions.assertThat(QuotePayloadParser.exchange(mapper.readTree(
                "{\"scripCode\":\"NF77351\"}"))).isEqualTo("NF");
        org.assertj.core.api.Assertions.assertThat(QuotePayloadParser.exchange(mapper.readTree(
                "{\"scripCode\":77351,\"exchange\":\"NFO\"}"))).isEqualTo("NF");
        org.assertj.core.api.Assertions.assertThat(QuotePayloadParser.exchange(mapper.readTree(
                "{\"scripCode\":77351}"))).isNull();
        org.assertj.core.api.Assertions.assertThat(QuotePayloadParser.exchange(mapper.readTree(
                "{\"scripCode\":\"NF77351\",\"exchange\":\"NSE\"}"))).isNull();
    }

}

