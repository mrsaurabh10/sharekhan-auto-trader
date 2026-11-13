package org.com.sharekhan.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.com.sharekhan.ws.QuotePayloadParser.BidAsk;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class QuotePayloadParserTest {
    @Test void quantitiesAreMatchedToTheirSideAndExactPrice() throws Exception {
        var data=MAPPER.readTree("{\"depth\":{\"buy\":[{\"price\":100,\"quantity\":65}],\"sell\":[{\"price\":101,\"qty\":130}]}}");
        var q=QuotePayloadParser.extractQuantities(data,QuotePayloadParser.extractBestBidAsk(data));
        assertEquals(65,q.bid());assertEquals(130,q.ask());
        q=QuotePayloadParser.extractQuantities(data,new BidAsk(99d,102d));assertNull(q.bid());assertNull(q.ask());
    }
    @Test void directQuantitiesDoNotUseTotalBuySellVolumeOrAmbiguousArrays() throws Exception {
        var data=MAPPER.readTree("{\"bestBidPrice\":100,\"bestAskPrice\":101,\"bestBidQty\":\"65\",\"bestAskQty\":0}");
        var q=QuotePayloadParser.extractQuantities(data,QuotePayloadParser.extractBestBidAsk(data));assertEquals(65,q.bid());assertEquals(0,q.ask());
        data=MAPPER.readTree("{\"bestBidPrice\":100,\"bestAskPrice\":101,\"totalBuyQuantity\":9999,\"totalSellQuantity\":9999,\"depth\":[{\"price\":100,\"quantity\":9999}]}");
        q=QuotePayloadParser.extractQuantities(data,QuotePayloadParser.extractBestBidAsk(data));assertNull(q.bid());assertNull(q.ask());
    }
    @Test void invalidOrFractionalQuantitiesStayUnknown() throws Exception {
        var data=MAPPER.readTree("{\"bestBidPrice\":100,\"bestAskPrice\":101,\"bestBidQty\":-1,\"bestAskQty\":0.5}");
        var q=QuotePayloadParser.extractQuantities(data,QuotePayloadParser.extractBestBidAsk(data));assertNull(q.bid());assertNull(q.ask());
    }

    @Test void liveSharekhanOffQtyAndInitialFeedBatchAreParsed() throws Exception {
        var batch=MAPPER.readTree("[{\"exchangeCode\":\"NF\",\"scripCode\":64159,\"bidPrice\":113.2,\"bidQty\":65,\"offPrice\":114.2,\"offQty\":130}]");
        var rows=WebSocketClientService.feedRows(batch);assertEquals(1,rows.size());
        var book=QuotePayloadParser.extractBestBidAsk(rows.get(0));var size=QuotePayloadParser.extractQuantities(rows.get(0),book);
        assertEquals(113.2,book.bid());assertEquals(114.2,book.ask());assertEquals(65,size.bid());assertEquals(130,size.ask());
        assertEquals("NF",QuotePayloadParser.exchange(rows.get(0)));
        assertEquals(1,WebSocketClientService.feedRows(rows.get(0)).size());
        assertTrue(WebSocketClientService.feedRows(null).isEmpty());assertTrue(WebSocketClientService.feedRows(MAPPER.readTree("\"not a quote\"")).isEmpty());
    }
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

