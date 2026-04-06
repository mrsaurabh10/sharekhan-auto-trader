package org.com.sharekhan.util;

import com.sharekhan.SharekhanConnect;
import com.sharekhan.http.exceptions.SharekhanAPIException;
import com.sharekhan.model.OrderParams;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.json.JSONObject;

import java.io.IOException;
import java.time.LocalTime;
import java.time.ZoneId;

public class ShareKhanOrderUtil {

    public static boolean isAfterHours() {
        LocalTime now = LocalTime.now(ZoneId.of("Asia/Kolkata"));
        // 6 PM to 9 PM
        boolean evening = !now.isBefore(LocalTime.of(17, 0)) && !now.isAfter(LocalTime.of(21, 0));
        // 11 PM to 8:58 AM
        boolean night = !now.isBefore(LocalTime.of(23, 0));
        boolean morning = !now.isAfter(LocalTime.of(8, 58));

        return evening || night || morning;
    }

    public static JSONObject modifyOrder(SharekhanConnect sharekhanConnect,
                                         TriggeredTradeSetupEntity tradeSetupEntity,
                                         Double orderPrice,
                                         Long customerId,
                                         String channelUser) throws SharekhanAPIException, IOException {
        OrderParams orderParams = new OrderParams();
        if(TriggeredTradeStatus.EXIT_ORDER_PLACED.equals(tradeSetupEntity.getStatus())
                || TriggeredTradeStatus.TARGET_ORDER_PLACED.equals(tradeSetupEntity.getStatus()) ){
            orderParams.orderId = tradeSetupEntity.getExitOrderId();
        }else{
            orderParams.orderId = tradeSetupEntity.getOrderId();
        }
        orderParams.customerId = customerId;
        orderParams.scripCode = tradeSetupEntity.getScripCode();
        orderParams.disclosedQty = (long) 0;
        orderParams.validity = "GFD";
        orderParams.quantity = tradeSetupEntity.getQuantity() != null ? tradeSetupEntity.getQuantity().longValue() : 0L;
        orderParams.symbolToken = "1660";
        orderParams.exchange = tradeSetupEntity.getExchange();
        orderParams.orderType ="NORMAL";
        orderParams.tradingSymbol = tradeSetupEntity.getSymbol();
        orderParams.productType = "INVESTMENT"; //(INVESTMENT or (INV), BIGTRADE or (BT), BIGTRADEPLUS or (BT+))
        // Transaction type should match the intent: entry orders are Buys (B), exit modifications must be Sell (S)
        if (TriggeredTradeStatus.EXIT_ORDER_PLACED.equals(tradeSetupEntity.getStatus())
                || TriggeredTradeStatus.TARGET_ORDER_PLACED.equals(tradeSetupEntity.getStatus())) {
            orderParams.transactionType = "S";
        } else {
            orderParams.transactionType = "B";
        }
        orderParams.price = String.valueOf(orderPrice);
        orderParams.triggerPrice = "0";
        orderParams.rmsCode= "ANY";
        orderParams.afterHour= isAfterHours() ? "Y" : "N";
        orderParams.channelUser=channelUser; // client/user code from broker credentials
        orderParams.requestType="MODIFY"; //
        orderParams.instrumentType=tradeSetupEntity.getInstrumentType(); //(Future Stocks(FS)/ Future Index(FI)/ Option Index(OI)/ Option Stocks(OS)/ Future Currency(FUTCUR)/ Option Currency(OPTCUR))
        // For NC/BC (equities) these may be null — set only when present to avoid sending the literal "null"
        if (tradeSetupEntity.getStrikePrice() != null) {
            orderParams.strikePrice = String.valueOf(tradeSetupEntity.getStrikePrice());
        } else {
            orderParams.strikePrice = null;
        }
        orderParams.optionType = (tradeSetupEntity.getOptionType() != null && !tradeSetupEntity.getOptionType().isBlank()) ? tradeSetupEntity.getOptionType() : null;
        orderParams.expiry = (tradeSetupEntity.getExpiry() != null && !tradeSetupEntity.getExpiry().isBlank()) ? tradeSetupEntity.getExpiry() : null;
        try {
            return SharekhanConsoleSilencer.call(() -> sharekhanConnect.modifyorder(orderParams));
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Sharekhan modifyorder interrupted: " + e.getMessage(), e);
        }
    }
}
