package org.com.sharekhan.util;

import com.sharekhan.SharekhanConnect;
import com.sharekhan.http.exceptions.SharekhanAPIException;
import com.sharekhan.model.OrderParams;
import org.com.sharekhan.auth.TokenLoginAutomationService;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.json.JSONObject;

import java.io.IOException;

public class ShareKhanOrderUtil {

    public static JSONObject modifyOrder(SharekhanConnect sharekhanConnect,TriggeredTradeSetupEntity tradeSetupEntity, Double orderPrice) throws SharekhanAPIException, IOException {
        OrderParams orderParams = new OrderParams();
        if(TriggeredTradeStatus.EXIT_ORDER_PLACED.equals(tradeSetupEntity.getStatus()) ){
            orderParams.orderId = tradeSetupEntity.getExitOrderId();
        }else{
            orderParams.orderId = tradeSetupEntity.getOrderId();
        }
        orderParams.customerId = (long) TokenLoginAutomationService.customerId;
        orderParams.scripCode = tradeSetupEntity.getScripCode();
        orderParams.disclosedQty = (long) 0;
        orderParams.validity = "GFD";
        orderParams.quantity = tradeSetupEntity.getQuantity().longValue();
        orderParams.symbolToken = "1660";
        orderParams.exchange = tradeSetupEntity.getExchange();
        orderParams.orderType ="NORMAL";
        orderParams.tradingSymbol = tradeSetupEntity.getSymbol();
        orderParams.productType = "INV"; //(INVESTMENT or (INV), BIGTRADE or (BT), BIGTRADEPLUS or (BT+))
        orderParams.transactionType = "B"; //( B, S, BM, SM, SAM)
        orderParams.price = String.valueOf(orderPrice);
        orderParams.triggerPrice = "0";
        orderParams.rmsCode= "ANY";
        orderParams.afterHour= "N";
        orderParams.channelUser=TokenLoginAutomationService.clientCode; //enter the customerid
        orderParams.requestType="MODIFY"; //
        orderParams.instrumentType=tradeSetupEntity.getInstrumentType(); //(Future Stocks(FS)/ Future Index(FI)/ Option Index(OI)/ Option Stocks(OS)/ Future Currency(FUTCUR)/ Option Currency(OPTCUR))
        orderParams.strikePrice= String.valueOf(tradeSetupEntity.getStrikePrice());
        orderParams.optionType=tradeSetupEntity.getOptionType();// (XX/PE/CE)
        orderParams.expiry= tradeSetupEntity.getExpiry();
        JSONObject order = sharekhanConnect.modifyorder(orderParams);
        return order;

    }
}
