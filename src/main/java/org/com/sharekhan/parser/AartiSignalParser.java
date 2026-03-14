package org.com.sharekhan.parser;

import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.regex.*;

public class AartiSignalParser implements TradingSignalParser {

    @Override
    public Map<String, Object> parse(String text) {
        if (text == null || text.isBlank()) return null;

        try {
            // Updated regex to handle decimal strike price
            Pattern mainPattern = Pattern.compile(
                    "(BUY|SELL)\\s+([A-Z]+)\\s+(\\d+(?:\\.\\d+)?)\\s+(CE|PE)",
                    Pattern.CASE_INSENSITIVE
            );
            Matcher m = mainPattern.matcher(text);
            if (!m.find()) return null;

            String action = m.group(1).toUpperCase();
            String symbol = m.group(2).toUpperCase();
            String strike = m.group(3);
            String optionType = m.group(4).toUpperCase();

            // Updated regex to capture decimal entry price
            String entryStr = null, target1Str = null, target2Str = null, slStr = null;
            Matcher entryM = Pattern.compile("(?:BUY|SELL)?\\s*ABOVE\\s*(\\d+(?:\\.\\d+)?)", Pattern.CASE_INSENSITIVE).matcher(text);
            if (entryM.find()) entryStr = entryM.group(1);

            Matcher tgtM = Pattern.compile("(?:TGT|TARGET)[\\s:]+(\\d+(?:\\.\\d+)?)[\\s/-]+(\\d+(?:\\.\\d+)?)", Pattern.CASE_INSENSITIVE).matcher(text);
            if (tgtM.find()) {
                target1Str = tgtM.group(1);
                target2Str = tgtM.group(2);
            }

            Matcher slM = Pattern.compile("(?:SL|STOP ?LOSS)\\s*(\\d+(?:\\.\\d+)?)", Pattern.CASE_INSENSITIVE).matcher(text);
            if (slM.find()) slStr = slM.group(1);
            
            // --- NEW: Parse Lots ---
            Matcher lotsM = Pattern.compile("LOTS\\s*(\\d+)", Pattern.CASE_INSENSITIVE).matcher(text);
            Integer lots = null;
            if (lotsM.find()) {
                try {
                    lots = Integer.parseInt(lotsM.group(1));
                } catch (NumberFormatException ignored) {}
            }
            // -----------------------

            Double entryPrice = tryParseDouble(entryStr);
            Double target1 = tryParseDouble(target1Str);
            Double target2 = tryParseDouble(target2Str);
            Double stopLoss = tryParseDouble(slStr);

            // Expiry
            String expiry = null; // Let the service calculate expiry if not present or specific logic needed
            // If you want to calculate simplistic expiry:
            // expiry = calculateNearestExpiry(symbol); 
            // BUT: better to let service find nearest valid expiry if we return null here?
            // Existing code calculated it, so let's keep it but make sure it returns format dd/MM/yyyy
             expiry = calculateNearestExpiry(symbol);


            Map<String, Object> result = new HashMap<>();
            result.put("source", "nifty-signal");
            result.put("action", action);
            result.put("symbol", symbol);
            result.put("strike", tryParseDouble(strike));
            result.put("optionType", optionType);
            result.put("entry", entryPrice);
            result.put("target1", target1);
            result.put("target2", target2);
            result.put("target3", null);
            result.put("stopLoss", stopLoss);
            result.put("trailingSl", 0.0);
            result.put("quantity", lots); // Put lots in quantity field (service handles it)
            result.put("expiry", expiry);
            result.put("exchange", null);
            result.put("intraday", true);

            return result;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private Double tryParseDouble(String val) {
        if (val == null || val.isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(val.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Determine expiry:
     * - NIFTY → next Thursday (weekly) - updated logic to be safe
     * - BANKNIFTY / FINNIFTY / MIDCPNIFTY → last Tuesday/Wednesday/Thursday depending on contract
     * - SENSEX → next Friday
     * - All other stocks → last Thursday of the month
     *
     * Note: This hardcoded logic might be brittle.
     * Ideally, we should parse expiry from text if available, or rely on service to find nearest.
     */
    private String calculateNearestExpiry(String symbol) {
        // Simple logic for now: just return null and let TradeExecutionService find nearest valid expiry
        // based on script master. This is safer than guessing wrong day.
        return null;
    }
}
