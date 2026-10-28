package org.com.sharekhan.service.broker;

import org.json.JSONObject;
import org.json.JSONArray;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class OptionBuyingFundsTest {
    JSONObject sharekhan() {
        return new JSONObject().put("status",200).put("data",new JSONArray().put(new JSONObject()
            .put("customerId",123L).put("currentCashBalance",10000).put("pendingWithdrawalRequest",100)
            .put("cashBpl",0).put("fnoBpl",0).put("cashPreviousSettlementExposure",0)
            .put("intradayMarginCash",-1000).put("fnoPremium","500.00").put("intradayMarginFno","200.00")
            .put("holdFunds",300).put("nonCashLimit",999999).put("limitAgainstShares",999999)));
    }
    @Test void sharekhanUsesCashLowerBoundWithoutCollateralAndDeductsSignedUtilisation() {
        assertThat(OptionBuyingFunds.sharekhan(sharekhan(),123L)).hasValue(7900);
    }
    @Test void sharekhanRejectsWrongAccountErrorsMissingAndAmbiguousRows() {
        assertThat(OptionBuyingFunds.sharekhan(sharekhan(),999L)).isEmpty();
        var missing=sharekhan();missing.getJSONArray("data").getJSONObject(0).remove("holdFunds");
        assertThat(OptionBuyingFunds.sharekhan(missing,123L)).isEmpty();
        assertThat(OptionBuyingFunds.sharekhan(sharekhan().put("status",401),123L)).isEmpty();
        var multiple=sharekhan();multiple.getJSONArray("data").put(new JSONObject());
        assertThat(OptionBuyingFunds.sharekhan(multiple,123L)).isEmpty();
    }
    JSONObject mstock() {
        return new JSONObject().put("status","success").put("data",new JSONArray().put(new JSONObject()
            .put("SEG","A").put("LIMIT_TYPE","CAPITAL").put("AVAILABLE_BALANCE","10000.50")
            .put("MTF_AVAILABLE_BALANCE","9999999")));
    }
    @Test void mstockUsesDocumentedCapitalBalanceAndRejectsOtherSegmentsAndMalformedValues() {
        assertThat(OptionBuyingFunds.mstock(mstock())).hasValue(10000.50);
        var wrong=mstock();wrong.getJSONArray("data").getJSONObject(0).put("SEG","OTHER");
        assertThat(OptionBuyingFunds.mstock(wrong)).isEmpty();
        var missing=mstock();missing.getJSONArray("data").getJSONObject(0).remove("AVAILABLE_BALANCE");
        assertThat(OptionBuyingFunds.mstock(missing)).isEmpty();
        var nan=mstock();nan.getJSONArray("data").getJSONObject(0).put("AVAILABLE_BALANCE","NaN");
        assertThat(OptionBuyingFunds.mstock(nan)).isEmpty();
        assertThat(OptionBuyingFunds.mstock(mstock().put("status","error"))).isEmpty();
    }
}
