package org.com.sharekhan.ws;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

final class QuotePayloadParser {

    private QuotePayloadParser() {
    }

    record BidAsk(Double bid, Double ask) {}
    record Quantities(Double bid, Double ask) {}

    /** Quantities belong to the exact side/price in this message, never total-market volume. */
    static Quantities extractQuantities(JsonNode data,BidAsk best) {
        if(data==null || best==null)return new Quantities(null,null);
        Double bid=quantityAtDirectPrice(data,DIRECT_BID_KEYS,Set.of("bestBidQty","bestBidQuantity","bidQty","bidQuantity"),best.bid());
        Double ask=quantityAtDirectPrice(data,DIRECT_ASK_KEYS,Set.of("bestAskQty","bestAskQuantity","bestOfferQty","askQty","askQuantity","offerQty"),best.ask());
        var depth=data.get("depth");
        if(depth!=null && depth.isObject()){
            if(bid==null)bid=quantityAtDepthPrice(depth,DEPTH_BID_KEYS,best.bid());
            if(ask==null)ask=quantityAtDepthPrice(depth,DEPTH_ASK_KEYS,best.ask());
        }
        return new Quantities(bid,ask);
    }
    private static Double quantityAtDirectPrice(JsonNode data,Set<String> priceKeys,Set<String> quantityKeys,Double expected){
        var price=findDirectPrice(data,priceKeys);
        return samePrice(price,expected)?findQuantity(data,quantityKeys):null;
    }
    private static Double quantityAtDepthPrice(JsonNode depth,Set<String> sideKeys,Double expected){
        var fields=depth.fieldNames();
        while(fields.hasNext()){
            var key=fields.next();
            if(!sideKeys.contains(key.toLowerCase(Locale.ROOT)))continue;
            var levels=depth.get(key);
            if(levels==null || !levels.isArray() || levels.isEmpty())continue;
            var top=levels.get(0);
            if(top.isObject() && samePrice(extractPriceFromDepthEntry(top),expected))return findQuantity(top,Set.of("quantity","qty","q"));
        }
        return null;
    }
    private static boolean samePrice(Double a,Double b){return a!=null && b!=null && Double.isFinite(a) && Double.isFinite(b) && Math.abs(a-b)<1e-8;}
    private static Double findQuantity(JsonNode node,Set<String> keys){
        for(var k:keys){
            var v=node.get(k);if(v==null || !(v.isNumber() || v.isTextual()))continue;
            try{double q=Double.parseDouble(v.asText());if(Double.isFinite(q) && q>=0 && q==Math.rint(q))return q;}catch(NumberFormatException ignored){ }
        }
        return null;
    }

    /** Only a provider-echoed exchange or prefixed token establishes the feed segment. */
    static String exchange(JsonNode data) {
        String result = null;
        for (String field : java.util.List.of("exchange", "Exchange", "exchangeCode", "ExchangeCode", "exch",
                "scripCode", "ScripCode", "scripcode", "scrip_code", "scrip", "token")) {
            JsonNode value = data.get(field);
            if (value == null || !value.isTextual()) continue;
            String text = value.asText().trim().toUpperCase(Locale.ROOT);
            String candidate = switch (text) {
                case "NF", "NFO" -> "NF";
                case "BF", "BFO" -> "BF";
                case "NC", "NSE" -> "NC";
                case "BC", "BSE" -> "BC";
                default -> text.matches("(NF|BF|NC|BC)[0-9]+") ? text.substring(0, 2) : null;
            };
            if (candidate == null) continue;
            if (result != null && !result.equals(candidate)) return null;
            result = candidate;
        }
        return result;
    }

    static BidAsk extractBestBidAsk(JsonNode data) {
        if (data == null || data.isMissingNode()) {
            return new BidAsk(null, null);
        }

        Double bid = findDirectPrice(data, DIRECT_BID_KEYS);
        Double ask = findDirectPrice(data, DIRECT_ASK_KEYS);

        if ((bid == null || ask == null) && data.hasNonNull("depth")) {
            JsonNode depthNode = data.get("depth");
            if (depthNode != null) {
                if (bid == null) {
                    bid = extractFromDepthArray(depthNode, DEPTH_BID_KEYS);
                }
                if (ask == null) {
                    ask = extractFromDepthArray(depthNode, DEPTH_ASK_KEYS);
                }
            }
        }

        return new BidAsk(bid, ask);
    }

    private static Double findDirectPrice(JsonNode node, Set<String> keys) {
        for (String key : keys) {
            if (!node.hasNonNull(key)) continue;
            Double value = numericValue(node.get(key));
            if (value != null && value > 0) {
                return value;
            }
        }
        return null;
    }

    private static Double extractFromDepthArray(JsonNode depthNode, Set<String> arrayKeys) {
        if (depthNode.isArray()) {
            for (JsonNode entry : depthNode) {
                Double price = extractPriceFromDepthEntry(entry);
                if (price != null && price > 0) {
                    return price;
                }
            }
            return null;
        }

        Iterator<String> fieldNames = depthNode.fieldNames();
        while (fieldNames.hasNext()) {
            String key = fieldNames.next();
            String normalized = key.toLowerCase(Locale.ROOT);
            if (!arrayKeys.contains(normalized)) {
                continue;
            }
            JsonNode levelArray = depthNode.get(key);
            if (levelArray == null || !levelArray.isArray() || levelArray.isEmpty()) {
                continue;
            }
            Double price = extractPriceFromDepthEntry(levelArray.get(0));
            if (price != null && price > 0) {
                return price;
            }
        }
        return null;
    }

    private static Double extractPriceFromDepthEntry(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.asDouble();
        }
        if (node.isTextual()) {
            try {
                double parsed = Double.parseDouble(node.asText());
                return parsed > 0 ? parsed : null;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (node.isObject()) {
            Double value = findDirectPrice(node, PRICE_FIELD_KEYS);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Double numericValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            double value = node.asDouble();
            return value > 0 ? value : null;
        }
        if (node.isTextual()) {
            try {
                double parsed = Double.parseDouble(node.asText());
                return parsed > 0 ? parsed : null;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static final Set<String> DIRECT_BID_KEYS = Set.of(
            "bestBidPrice", "bestBid", "bidPrice", "buyPrice", "bid"
    );

    private static final Set<String> DIRECT_ASK_KEYS = Set.of(
            "bestAskPrice", "bestOfferPrice", "askPrice", "sellPrice", "offer", "ask", "offPrice"
    );

    private static final Set<String> PRICE_FIELD_KEYS = Set.of(
            "price", "p", "ltp", "lastPrice"
    );

    private static final Set<String> DEPTH_BID_KEYS = Set.of(
            "buy", "bid", "bids", "buydepth", "bid depth"
    );

    private static final Set<String> DEPTH_ASK_KEYS = Set.of(
            "sell", "ask", "asks", "offer", "selldepth", "offer depth"
    );
}
