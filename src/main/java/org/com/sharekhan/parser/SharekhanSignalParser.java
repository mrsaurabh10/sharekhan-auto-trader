package org.com.sharekhan.parser;

import java.util.*;
import java.util.regex.*;

public class SharekhanSignalParser implements TradingSignalParser {

    @Override
    public Map<String, Object> parse(String text) {
        if (text == null || !text.contains("Sharekhan Signal:")) {
            return null;
        }

        // Extract the JSON-like content between { and }
        int startIndex = text.indexOf('{');
        int endIndex = text.lastIndexOf('}');
        
        if (startIndex == -1 || endIndex == -1 || startIndex >= endIndex) {
            return null;
        }

        String content = text.substring(startIndex + 1, endIndex);
        Map<String, String> data = new HashMap<>();

        // Split by comma, but be careful about values that might contain commas (though unlikely in this format)
        String[] pairs = content.split(",");
        for (String pair : pairs) {
            String[] keyValue = pair.split("=");
            if (keyValue.length == 2) {
                data.put(keyValue[0].trim(), keyValue[1].trim());
            }
        }

        try {
            String action = data.get("action");
            String symbol = data.get("symbol");
            String expiryStr = data.get("expiry");
            String optionType = data.get("optionType");
            String strikeStr = data.get("strike");
            String entryStr = data.get("entryPrice");
            String target1Str = data.get("target1");
            String target2Str = data.get("target2");
            String stopLossStr = data.get("stopLoss");
            
            // source is usually "sharekhan", but we can just hardcode or take from map
            String source = data.getOrDefault("source", "sharekhan");

            if (action == null || symbol == null || optionType == null || strikeStr == null || entryStr == null) {
                return null;
            }

            Double strike = Double.parseDouble(strikeStr);
            Double entry = Double.parseDouble(entryStr);
            Double target1 = target1Str != null && !target1Str.equals("null") ? Double.parseDouble(target1Str) : null;
            Double target2 = target2Str != null && !target2Str.equals("null") ? Double.parseDouble(target2Str) : null;
            Double stopLoss = stopLossStr != null && !stopLossStr.equals("null") ? Double.parseDouble(stopLossStr) : null;

            // Format expiry to dd/MM/yyyy if present
            String expiryFormatted = null;
            if (expiryStr != null && !expiryStr.equals("null") && !expiryStr.isBlank()) {
                // Input format: 30-Mar-2026
                // Output format: 30/03/2026
                 try {
                     java.time.format.DateTimeFormatter inputFormatter = java.time.format.DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH);
                     java.time.LocalDate date = java.time.LocalDate.parse(expiryStr, inputFormatter);
                     expiryFormatted = date.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"));
                 } catch (Exception e) {
                     // If parsing fails, leave null or try other formats? 
                     // The requirement says "send expiry as null" on receiving this, but let's parse if we can, 
                     // and if the user wants to force null, we can do it in the result map or handle it.
                     // The user request says: "trigger the order api on receving this send expiry as null".
                     // So we should probably return null for expiry in the map.
                     expiryFormatted = null;
                 }
            }

            // User requirement: "trigger the order api on receving this send expiry as null"
            // So we explicitly set expiry to null.
            expiryFormatted = null;

            Map<String, Object> result = new HashMap<>();
            result.put("source", source);
            result.put("action", action);
            result.put("symbol", symbol);
            result.put("strike", strike);
            result.put("optionType", optionType);
            result.put("entry", entry);
            result.put("target1", target1);
            result.put("target2", target2);
            result.put("stopLoss", stopLoss);
            result.put("expiry", expiryFormatted); // Explicitly null as requested
            result.put("exchange", null); // Will be determined by service
            result.put("intraday", true);
            
            // Check for quantity/lots if present in data map (not in example but good practice)
            if (data.containsKey("quantity")) {
                try {
                    result.put("quantity", Integer.parseInt(data.get("quantity")));
                } catch (NumberFormatException ignored) {}
            }

            return result;

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
