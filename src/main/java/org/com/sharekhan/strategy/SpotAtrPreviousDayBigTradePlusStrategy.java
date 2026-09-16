package org.com.sharekhan.strategy;

import org.com.sharekhan.dto.StrategyApplyRequest;
import org.com.sharekhan.dto.StrategyApplyResponse;
import org.com.sharekhan.dto.TriggerRequest;
import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.entity.TriggerTradeRequestEntity;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Long-only spot ATR prior-day breakout, submitted as three Sharekhan BTP brackets. */
@Component
public class SpotAtrPreviousDayBigTradePlusStrategy implements StrategyEvaluator {
    public static final String TEMPLATE_ID = "SPOT_ATR_PDH_BIGTRADEPLUS";
    public static final String SOURCE = "spot-atr-pdh-bigtradeplus";
    private static final double STOP_ATR = 2d;
    private static final double[] TARGET_ATR = {3d, 5d, 6d};

    private final StrategySupport support;
    private final AtrPreviousDayBreakoutQualificationService qualificationService;

    protected boolean sellSide() { return false; }
    protected String source() { return SOURCE; }
    protected String templateId() { return TEMPLATE_ID; }

    public SpotAtrPreviousDayBigTradePlusStrategy(StrategySupport support,
                                                  AtrPreviousDayBreakoutQualificationService qualificationService) {
        this.support = support;
        this.qualificationService = qualificationService;
    }

    @Override public StrategyMetadata metadata() {
        return new StrategyMetadata(templateId(), sellSide() ? "Spot ATR Previous-Day BIGTRADEPLUS Bearish" : "Spot ATR Previous-Day BIGTRADEPLUS",
                sellSide() ? "NSE cash breakdown below prior-day low; creates three sell BIGTRADEPLUS brackets."
                        : "Long-only NSE cash breakout above prior-day high; creates three BIGTRADEPLUS bracket orders.", "");
    }

    @Override public StrategyApplyResponse apply(StrategyApplyRequest request) {
        if (request.getLots() == null || request.getLots() < 3) {
            throw new IllegalArgumentException("Quantity must be at least 3 shares so BIGTRADEPLUS can create three legs");
        }
        String symbol = request.getSymbol().trim().toUpperCase();
        // Unlike the F&O ATR templates, a BTP strategy is one entry attempt per
        // cash symbol per day. Once any bracket leg has reached the broker, do
        // not create another three-leg group after it exits or is rejected.
        if (support.hasEntryForSymbolOn(source(), LocalDateTime.now(StrategySupport.MARKET_ZONE).toLocalDate(),
                request.getUserId(), symbol)) {
            return StrategyApplyResponse.builder().status("duplicate")
                    .message(symbol + ": a BIGTRADEPLUS ATR entry was already submitted today")
                    .templateId(templateId()).symbol(symbol).direction(sellSide() ? "SELL" : "BUY").build();
        }
        ScriptMasterEntity spot = support.resolveSpotScript(symbol);
        var qualification = qualificationService.qualify(spot, sellSide() ? "PE" : "CE", request.getUserId(), LocalDateTime.now(StrategySupport.MARKET_ZONE));
        if (!qualification.qualified()) return support.waiting(metadata(), symbol, qualification.reason());

        TriggerRequest probe = baseRequest(request, spot, qualification.signal(), 1, TARGET_ATR[0]);
        TriggerTradeRequestEntity existing = support.findActiveSetup(probe, source());
        if (existing != null) return StrategyApplyResponse.builder().status("duplicate")
                .message(symbol + ": BIGTRADEPLUS ATR setup is already active").templateId(templateId())
                .symbol(symbol).triggerRequest(probe).tradeRequest(existing).build();

        List<Integer> quantities = split(request.getLots());
        List<TriggerTradeRequestEntity> legs = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            legs.add(support.createPendingTradeRequest(baseRequest(request, spot, qualification.signal(), quantities.get(i), TARGET_ATR[i])));
        }
        return StrategyApplyResponse.builder().status("triggered")
                .message(symbol + ": created BIGTRADEPLUS legs with quantities " + quantities)
                .templateId(templateId()).symbol(symbol).direction(sellSide() ? "SELL" : "BUY")
                .breakoutClose(support.roundPrice(qualification.signal().entryPrice()))
                .triggerRequest(probe).tradeRequest(legs.get(0)).build();
    }

    private TriggerRequest baseRequest(StrategyApplyRequest request, ScriptMasterEntity spot,
                                       Fno925EntryQualificationService.Signal signal, int quantity, double targetMultiplier) {
        double entry = signal.entryPrice(), atr = signal.atr();
        if (!Double.isFinite(atr) || atr <= 0d) throw new IllegalArgumentException("ATR(75) is unavailable for " + spot.getTradingSymbol());
        TriggerRequest trade = new TriggerRequest();
        trade.setInstrument(spot.getTradingSymbol()); trade.setExchange("NC"); trade.setEntryPrice(support.roundPrice(entry));
        trade.setStopLoss(support.roundPrice(entry + (sellSide() ? 1 : -1) * STOP_ATR * atr));
        trade.setTarget1(support.roundPrice(entry + (sellSide() ? -1 : 1) * targetMultiplier * atr));
        trade.setQuantity(quantity); trade.setIntraday(request.getIntraday() == null || request.getIntraday());
        trade.setUserId(request.getUserId()); trade.setBrokerCredentialsId(request.getBrokerCredentialsId());
        if (sellSide()) trade.setIntraday(true);
        trade.setSource(source()); trade.setBrokerProductType("BIGTRADEPLUS");
        return trade;
    }

    private List<Integer> split(int quantity) {
        int base = quantity / 3;
        return List.of(base, base, quantity - 2 * base);
    }
}
