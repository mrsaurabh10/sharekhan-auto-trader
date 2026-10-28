package org.com.sharekhan.service.broker;

import java.util.OptionalDouble;
import org.json.JSONObject;

/** Strict funds adapters. Never turn absent/error fields into zero or borrowing capacity. */
public final class OptionBuyingFunds {
    private OptionBuyingFunds() {}

    public static OptionalDouble sharekhan(JSONObject response, Long customerId) {
        try {
            if (response.getInt("status") != 200) return OptionalDouble.empty();
            var rows = response.getJSONArray("data");
            if (rows.length() != 1) return OptionalDouble.empty();
            var row = rows.getJSONObject(0);
            if (row.getLong("customerId") != customerId) return OptionalDouble.empty();
            // Cash-only lower bound: do not add collateral, unrealised profits or leverage.
            // Conservatively deduct signed utilisation fields in either direction.
            double cash = number(row, "currentCashBalance");
            for (String key : new String[]{"pendingWithdrawalRequest", "cashBpl", "fnoBpl",
                    "cashPreviousSettlementExposure", "intradayMarginCash", "fnoPremium",
                    "intradayMarginFno", "holdFunds"}) cash -= Math.abs(number(row, key));
            return Double.isFinite(cash) ? OptionalDouble.of(cash) : OptionalDouble.empty();
        } catch (Exception ignored) { return OptionalDouble.empty(); }
    }

    public static OptionalDouble mstock(JSONObject response) {
        try {
            if (!"success".equals(response.getString("status"))) return OptionalDouble.empty();
            var rows = response.getJSONArray("data");
            if (rows.length() != 1) return OptionalDouble.empty();
            var row = rows.getJSONObject(0);
            // Only the documented combined capital segment; never select a random segment.
            if (!"A".equals(row.getString("SEG")) || !"CAPITAL".equals(row.getString("LIMIT_TYPE"))) return OptionalDouble.empty();
            return OptionalDouble.of(number(row, "AVAILABLE_BALANCE"));
        } catch (Exception ignored) { return OptionalDouble.empty(); }
    }

    private static double number(JSONObject row, String key) {
        double value = row.getDouble(key);
        if (!Double.isFinite(value)) throw new IllegalArgumentException();
        return value;
    }
}
