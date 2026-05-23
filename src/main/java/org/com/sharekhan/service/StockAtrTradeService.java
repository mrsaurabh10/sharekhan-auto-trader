package org.com.sharekhan.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.dto.StockAtrTradeRequest;
import org.com.sharekhan.dto.StockAtrTradeResponse;
import org.com.sharekhan.dto.TriggerRequest;
import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.repository.ScriptMasterRepository;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Slf4j
public class StockAtrTradeService {

    private static final int ATR_PERIOD = 14;
    private static final String FIVE_MINUTE_INTERVAL = "5minute";
    private static final String FIFTEEN_MINUTE_INTERVAL = "15minute";
    private static final double TARGET3_ATR15_MULTIPLIER = 1.0d;
    private static final double TARGET3_FIVE_MINUTE_FALLBACK_MULTIPLIER = 6.0d;
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter EXPIRY_FORMAT = DateTimeFormatter.ofPattern("dd/MM/uuuu");

    private final ScriptMasterRepository scriptMasterRepository;
    private final SharekhanHistoricalService historicalService;

    public StockAtrTradeResponse triggerForAllUsers(StockAtrTradeRequest request) {
        TriggerRequest triggerRequest = buildTriggerRequest(request);
        return buildResponse(triggerRequest, request.getDirection());
    }

    public TriggerRequest buildTriggerRequest(StockAtrTradeRequest request) {
        validateRequest(request);

        String stock = request.getStock().trim().toUpperCase(Locale.ROOT);
        String direction = normalizeDirection(request.getDirection());
        String optionType = "LONG".equals(direction) ? "CE" : "PE";
        double entry = request.getEntryPrice();

        ScriptMasterEntity spot = resolveSpotScript(stock);
        double atr = calculateAtr(spot.getScripCode(), FIVE_MINUTE_INTERVAL);
        double atr15m = calculateAtr(spot.getScripCode(), FIFTEEN_MINUTE_INTERVAL);
        double stopLoss = "LONG".equals(direction)
                ? entry - (1.5d * atr)
                : entry + (1.5d * atr);
        double target1 = "LONG".equals(direction)
                ? entry + (3.0d * atr)
                : entry - (3.0d * atr);
        double target2 = "LONG".equals(direction)
                ? entry + (5.0d * atr)
                : entry - (5.0d * atr);
        double target3 = calculateTarget3(entry, direction, atr, atr15m, target2);

        String expiry = nearestExpiry(stock, optionType);
        double atmReferencePrice = entry;
        double strike = nearestStrike(stock, optionType, expiry, atmReferencePrice);

        log.info("📐 ATR stock trade computed | stock={} spotScrip={} direction={} optionType={} entry={} atrPeriod={} atr5m={} atr15m={} stopLoss={} target1={} target2={} target3={} atmReference=spotEntry atmReferencePrice={} strike={} expiry={}",
                stock, spot.getScripCode(), direction, optionType, roundPrice(entry), ATR_PERIOD,
                atr, atr15m, roundPrice(stopLoss), roundPrice(target1), roundPrice(target2), roundPrice(target3),
                roundPrice(atmReferencePrice), strike, expiry);

        TriggerRequest trigger = new TriggerRequest();
        trigger.setInstrument(stock);
        trigger.setEntryPrice(roundPrice(entry));
        trigger.setStopLoss(roundPrice(stopLoss));
        trigger.setTarget1(roundPrice(target1));
        trigger.setTarget2(roundPrice(target2));
        trigger.setTarget3(roundPrice(target3));
        trigger.setStrikePrice(strike);
        trigger.setOptionType(optionType);
        trigger.setExpiry(expiry);
        trigger.setIntraday(request.getIntraday() != null ? request.getIntraday() : true);
        trigger.setSource(StringUtils.hasText(request.getSource()) ? request.getSource().trim() : "admin-ui");
        trigger.setUseSpotPrice(true);
        trigger.setUseSpotForEntry(true);
        trigger.setUseSpotForSl(true);
        trigger.setUseSpotForTarget(true);
        trigger.setSpotScripCode(spot.getScripCode());
        if (request.getLots() != null && request.getLots() > 0) {
            trigger.setQuantity(request.getLots());
            trigger.setLots(request.getLots());
        }
        return trigger;
    }

    static double calculateTarget3(double entry, String direction, double atr5m, double atr15m, double target2) {
        boolean isLong = "LONG".equals(direction);
        double target3 = isLong
                ? entry + (TARGET3_ATR15_MULTIPLIER * atr15m)
                : entry - (TARGET3_ATR15_MULTIPLIER * atr15m);

        boolean target3BeyondTarget2 = isLong ? target3 >= target2 : target3 <= target2;
        if (target3BeyondTarget2) {
            return target3;
        }

        return isLong
                ? entry + (TARGET3_FIVE_MINUTE_FALLBACK_MULTIPLIER * atr5m)
                : entry - (TARGET3_FIVE_MINUTE_FALLBACK_MULTIPLIER * atr5m);
    }

    public StockAtrTradeResponse buildResponse(TriggerRequest triggerRequest, String direction) {
        return StockAtrTradeResponse.builder()
                .status("triggered")
                .stock(triggerRequest.getInstrument())
                .direction(normalizeDirection(direction))
                .optionType(triggerRequest.getOptionType())
                .entryPrice(triggerRequest.getEntryPrice())
                .atr(Math.abs(triggerRequest.getTarget1() - triggerRequest.getEntryPrice()) / 3.0d)
                .atrPeriod(ATR_PERIOD)
                .candleInterval(FIVE_MINUTE_INTERVAL)
                .stopLoss(triggerRequest.getStopLoss())
                .target(triggerRequest.getTarget1())
                .target1(triggerRequest.getTarget1())
                .target2(triggerRequest.getTarget2())
                .target3(triggerRequest.getTarget3())
                .atr15m(triggerRequest.getTarget3() != null
                        ? Math.abs(triggerRequest.getTarget3() - triggerRequest.getEntryPrice()) / TARGET3_ATR15_MULTIPLIER
                        : null)
                .strikePrice(triggerRequest.getStrikePrice())
                .expiry(triggerRequest.getExpiry())
                .spotScripCode(triggerRequest.getSpotScripCode())
                .message("Request submitted for all active users.")
                .build();
    }

    private void validateRequest(StockAtrTradeRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required");
        }
        if (!StringUtils.hasText(request.getStock())) {
            throw new IllegalArgumentException("stock is required");
        }
        if (request.getEntryPrice() == null || request.getEntryPrice() <= 0d || !Double.isFinite(request.getEntryPrice())) {
            throw new IllegalArgumentException("entryPrice must be greater than zero");
        }
        normalizeDirection(request.getDirection());
    }

    private String normalizeDirection(String direction) {
        if (!StringUtils.hasText(direction)) {
            throw new IllegalArgumentException("direction is required: LONG or SHORT");
        }
        String normalized = direction.trim().toUpperCase(Locale.ROOT);
        if ("BUY".equals(normalized) || "BULLISH".equals(normalized)) {
            normalized = "LONG";
        } else if ("SELL".equals(normalized) || "BEARISH".equals(normalized)) {
            normalized = "SHORT";
        }
        if (!"LONG".equals(normalized) && !"SHORT".equals(normalized)) {
            throw new IllegalArgumentException("direction must be LONG or SHORT");
        }
        return normalized;
    }

    private ScriptMasterEntity resolveSpotScript(String stock) {
        for (String candidate : SpotSymbolAliases.candidates(stock)) {
            ScriptMasterEntity script = findSpotScript(candidate, "NC");
            if (script != null) {
                return script;
            }
            script = findSpotScript(candidate, "BC");
            if (script != null) {
                return script;
            }
        }
        throw new IllegalArgumentException("Spot script not found for stock " + stock);
    }

    private ScriptMasterEntity findSpotScript(String stock, String exchange) {
        return scriptMasterRepository.findByExchangeIgnoreCase(exchange).stream()
                .filter(s -> s.getTradingSymbol() != null && s.getTradingSymbol().equalsIgnoreCase(stock))
                .filter(s -> s.getStrikePrice() == null || Math.abs(s.getStrikePrice()) < 0.001d)
                .filter(s -> !StringUtils.hasText(s.getExpiry()))
                .findFirst()
                .orElse(null);
    }

    private double calculateAtr(Integer spotScripCode, String candleInterval) {
        LocalDate today = LocalDate.now(MARKET_ZONE);
        List<SharekhanHistoricalService.HistoricalCandle> candles = historicalService
                .getHistoricalCandles(spotScripCode, candleInterval, today.minusDays(10), today)
                .stream()
                .filter(Objects::nonNull)
                .filter(c -> c.high() > 0d && c.low() > 0d && c.close() > 0d)
                .sorted(candleComparator())
                .toList();

        log.info("📐 ATR candle load | spotScrip={} interval={} period={} candles={} from={} to={}",
                spotScripCode, candleInterval, ATR_PERIOD, candles.size(), today.minusDays(10), today);

        int requiredCandles = ATR_PERIOD + 1;
        if (candles.size() < requiredCandles) {
            throw new IllegalArgumentException("Not enough " + candleInterval + " candles to compute ATR(14). Required "
                    + requiredCandles + ", found " + candles.size());
        }

        List<SharekhanHistoricalService.HistoricalCandle> tail = candles.subList(candles.size() - requiredCandles, candles.size());
        double trueRangeSum = 0d;
        for (int i = 1; i < tail.size(); i++) {
            SharekhanHistoricalService.HistoricalCandle current = tail.get(i);
            SharekhanHistoricalService.HistoricalCandle previous = tail.get(i - 1);
            double highLow = current.high() - current.low();
            double highPrevClose = Math.abs(current.high() - previous.close());
            double lowPrevClose = Math.abs(current.low() - previous.close());
            trueRangeSum += Math.max(highLow, Math.max(highPrevClose, lowPrevClose));
        }

        double atr = trueRangeSum / ATR_PERIOD;
        if (!Double.isFinite(atr) || atr <= 0d) {
            throw new IllegalArgumentException("Unable to compute a valid ATR(14)");
        }
        double roundedAtr = roundPrice(atr);
        SharekhanHistoricalService.HistoricalCandle last = tail.get(tail.size() - 1);
        log.info("📐 ATR computed | spotScrip={} interval={} period={} trueRangeSum={} atr={} roundedAtr={} lastCandleDate={} lastCandleTime={} lastClose={}",
                spotScripCode, candleInterval, ATR_PERIOD, roundPrice(trueRangeSum), atr, roundedAtr,
                last.date(), last.time(), last.close());
        return roundedAtr;
    }

    private Comparator<SharekhanHistoricalService.HistoricalCandle> candleComparator() {
        return Comparator
                .comparing((SharekhanHistoricalService.HistoricalCandle c) -> c.date() != null ? c.date() : LocalDate.MIN)
                .thenComparing(c -> c.time() != null ? c.time() : LocalTime.MIN);
    }

    private String nearestExpiry(String stock, String optionType) {
        LocalDateTime now = LocalDateTime.now(MARKET_ZONE);
        LocalTime expiryCutoff = LocalTime.of(15, 30);
        return scriptMasterRepository.findAllOptionExpiriesByTradingSymbolAndOptionType(stock, optionType)
                .stream()
                .filter(StringUtils::hasText)
                .map(this::parseExpiry)
                .filter(Objects::nonNull)
                .filter(expiry -> expiry.isAfter(now.toLocalDate())
                        || (expiry.isEqual(now.toLocalDate()) && now.toLocalTime().isBefore(expiryCutoff)))
                .min(Comparator.naturalOrder())
                .map(EXPIRY_FORMAT::format)
                .orElseThrow(() -> new IllegalArgumentException("No valid option expiry found for " + stock + " " + optionType));
    }

    private double nearestStrike(String stock, String optionType, String expiry, double entryPrice) {
        return scriptMasterRepository.findStrikePricesByTradingSymbolAndOptionTypeAndExpiry(stock, optionType, expiry)
                .stream()
                .filter(Objects::nonNull)
                .min(Comparator.comparingDouble(strike -> Math.abs(strike - entryPrice)))
                .orElseThrow(() -> new IllegalArgumentException("No strikes found for " + stock + " " + optionType + " " + expiry));
    }

    private LocalDate parseExpiry(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        for (DateTimeFormatter formatter : List.of(EXPIRY_FORMAT, DateTimeFormatter.ISO_LOCAL_DATE)) {
            try {
                return LocalDate.parse(raw.trim(), formatter);
            } catch (DateTimeParseException ignored) {
            }
        }
        return null;
    }

    private double roundPrice(double price) {
        return Math.round(price * 100.0d) / 100.0d;
    }
}
