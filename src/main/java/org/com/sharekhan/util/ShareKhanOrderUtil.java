package org.com.sharekhan.util;

import com.sharekhan.SharekhanConnect;
import com.sharekhan.http.exceptions.SharekhanAPIException;
import com.sharekhan.model.OrderParams;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.json.JSONObject;

import java.io.IOException;

public class ShareKhanOrderUtil {

    public static JSONObject modifyOrder(SharekhanConnect sharekhanConnect,
                                         TriggeredTradeSetupEntity tradeSetupEntity,
                                         Double orderPrice,
                                         Long customerId,
                                         String channelUser) throws SharekhanAPIException, IOException {
        OrderParams orderParams = new OrderParams();
        if(TriggeredTradeStatus.EXIT_ORDER_PLACED.equals(tradeSetupEntity.getStatus()) ){
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
        orderParams.productType = "INV"; //(INVESTMENT or (INV), BIGTRADE or (BT), BIGTRADEPLUS or (BT+))
        // Transaction type should match the intent: entry orders are Buys (B), exit modifications must be Sell (S)
        if (TriggeredTradeStatus.EXIT_ORDER_PLACED.equals(tradeSetupEntity.getStatus())) {
            orderParams.transactionType = "S";
        } else {
            orderParams.transactionType = "B";
        }
        orderParams.price = String.valueOf(orderPrice);
        orderParams.triggerPrice = "0";
        orderParams.rmsCode= "ANY";
        orderParams.afterHour= "N";
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
        JSONObject order = sharekhanConnect.modifyorder(orderParams);
        return order;

    }
}
