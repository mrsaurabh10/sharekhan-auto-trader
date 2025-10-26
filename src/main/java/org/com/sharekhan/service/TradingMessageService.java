package org.com.sharekhan.service;

import org.com.sharekhan.dto.TriggerRequest;
import org.com.sharekhan.parser.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class TradingMessageService {

    @Autowired
    private  TradeExecutionService tradingExecutorService;

    private final TradingSignalParser parserChain = new ParserChain(
            new TelegramSignalParser(),
            new WhatsappSignalParser(),
            new AartiSignalParser()
    );


    public void handleRawMessage(String message, String source) {
        Map<String, Object> parsed = parserChain.parse(message);
        if (parsed != null) {
            System.out.println("✅ Parsed message: " + parsed);
            tradingExecutorService.executeTrade(mapToTriggerRequest(parsed));
            // trigger trading logic
        } else {
            System.out.println("⚠️ No parser matched message");
        }
    }

    private TriggerRequest mapToTriggerRequest(Map<String, Object> parsed) {
        TriggerRequest request = new TriggerRequest();
        //request.setAction((String) parsed.get("action"));
        request.setInstrument((String) parsed.get("symbol"));
        request.setStrikePrice(parseDouble(parsed.get("strike")) );
        request.setOptionType((String) parsed.get("optionType"));
        request.setEntryPrice(parseDouble(parsed.get("entry")));
        request.setTarget1(parseDouble(parsed.get("target1")));
        request.setTarget2(parseDouble(parsed.get("target2")));
        request.setStopLoss(parseDouble(parsed.get("stopLoss")));
        request.setExpiry((String) parsed.get("expiry"));
        request.setExchange((String) parsed.get("exchange"));
        request.setIntraday(true);
        return request;
    }

    private Double parseDouble(Object val) {
        if (val instanceof Number) return ((Number) val).doubleValue();
        try {
            return Double.parseDouble(val.toString());
        } catch (Exception e) {
            return null;
        }
    }

}
