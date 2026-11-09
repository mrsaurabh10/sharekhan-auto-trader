package org.com.sharekhan.strategy;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.dto.StrategyApplyRequest;
import org.com.sharekhan.repository.BrokerCredentialsRepository;
import org.com.sharekhan.repository.ScriptMasterRepository;
import org.com.sharekhan.strategy.run.StrategyRiskSettings;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Simulator-only policy for the two existing Supertrend templates. */
@Slf4j
@Component
public class SupertrendSimulatorPolicy {
    private static final BigDecimal LOSS_PER_LOT = new BigDecimal("3000");
    private static final double MAX_STOP_ATR = 1.5d;
    private static final int ATR_PERIOD = 14;
    private static final DateTimeFormatter EXPIRY = DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(java.time.format.ResolverStyle.STRICT);
    private final BrokerCredentialsRepository brokers;
    private final ScriptMasterRepository scripts;
    private final boolean enabled;

    public SupertrendSimulatorPolicy(BrokerCredentialsRepository brokers, ScriptMasterRepository scripts,
            @Value("${app.strategy.supertrend.simulator-policy-enabled:true}") boolean enabled) {
        this.brokers = brokers;
        this.scripts = scripts;
        this.enabled = enabled;
    }

    @PostConstruct
    void describe() {
        log.info("SUPERTREND_SIMULATOR_POLICY | enabled={} | maxStopAtr=1.5 | atrPeriod=14 | lossPerOriginalLot=3000 | basis=GROSS_INR | NIFTY=NEXT_LISTED_ON_EXPIRY | BANKNIFTY=NEXT_CALENDAR_MONTH_ON_EXPIRY", enabled);
    }

    public boolean applies(StrategyApplyRequest request) {
        String template = request == null || request.getTemplateId() == null ? "" : request.getTemplateId().trim();
        if (!enabled || !("ST_RSI_EMA_ADX_CE".equalsIgnoreCase(template)
                || "ST_RSI_EMA_ADX_PE".equalsIgnoreCase(template))) return false;
        if (request.getBrokerCredentialsId() == null) throw new IllegalArgumentException("Select a broker account");
        var broker = brokers.findById(request.getBrokerCredentialsId())
                .orElseThrow(() -> new IllegalArgumentException("Broker account not found"));
        if (!"Simulator".equalsIgnoreCase(broker.getBrokerName())) return false;
        if (!Objects.equals(request.getUserId(), broker.getAppUserId()))
            throw new IllegalArgumentException("Simulator account owner mismatch");
        return true;
    }

    /** Preserve stricter existing stops and the units of other monetary controls. */
    public StrategyRiskSettings riskSettings(StrategyApplyRequest request) {
        if (!applies(request)) return StrategyRiskSettings.validate(request.getRiskSettings(), request.getLots());
        if (request.getLots() == null) request.setLots(1);
        if (request.getLots() < 1) throw new IllegalArgumentException("Supertrend lots must be positive");
        var rules = StrategyRiskSettings.validate(request.getRiskSettings(), request.getLots());
        if (rules == null) {
            rules = new StrategyRiskSettings();
            rules.setAmountMode(StrategyRiskSettings.AmountMode.PER_LOT);
        }
        var limit = rules.getAmountMode() == StrategyRiskSettings.AmountMode.TOTAL
                ? LOSS_PER_LOT.multiply(BigDecimal.valueOf(request.getLots())) : LOSS_PER_LOT;
        if (rules.getMaxLoss() == null || rules.getMaxLoss().compareTo(limit) > 0) rules.setMaxLoss(limit);
        return StrategyRiskSettings.validate(rules, request.getLots());
    }

    public String expiry(String symbol, String side, LocalDate today) {
        var dates = scripts.findAllOptionExpiriesByTradingSymbolAndOptionType(symbol, side).stream()
                .map(SupertrendSimulatorPolicy::parseExpiry).filter(Objects::nonNull)
                .filter(d -> !d.isBefore(today)).distinct().sorted().toList();
        if (dates.isEmpty()) throw new IllegalArgumentException("No listed expiry for " + symbol + " " + side);
        boolean expiryDay = dates.getFirst().equals(today);
        boolean bank = "BANKNIFTY".equalsIgnoreCase(symbol) || "NIFTYBANK".equalsIgnoreCase(symbol);
        return dates.stream().filter(d -> d.isAfter(today))
                .filter(d -> !expiryDay || !bank || YearMonth.from(d).equals(YearMonth.from(today).plusMonths(1)))
                .sorted(bank && expiryDay ? Comparator.reverseOrder() : Comparator.naturalOrder())
                .findFirst().map(EXPIRY::format)
                .orElseThrow(() -> new IllegalArgumentException("No later listed "
                        + (bank && expiryDay ? "month " : "") + "expiry for " + symbol + " " + side));
    }

    private static LocalDate parseExpiry(String value) {
        if (value == null) return null;
        for (var format : List.of(EXPIRY, DateTimeFormatter.ISO_LOCAL_DATE,
                new java.time.format.DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("dd-MMM-uuuu").toFormatter(Locale.ROOT).withResolverStyle(java.time.format.ResolverStyle.STRICT))) {
            try { return LocalDate.parse(value.trim(), format); }
            catch (DateTimeParseException ignored) { /* Try the broker's other supported formats. */ }
        }
        return null;
    }

    public SupertrendSignalRules.Failure stopFailure(List<StrategyCandle> completed, String side) {
        if (completed == null || completed.isEmpty()) return invalidAtr();
        var signal = completed.getLast();
        var earliest = signal.date().minusDays(14);
        var history = completed.stream().filter(c -> !c.date().isBefore(earliest)).toList();
        history = history.subList(Math.max(0, history.size() - 250), history.size());
        if (history.size() < 50) return invalidAtr();
        double previousClose = history.getFirst().close();
        double atr = 0d;
        for (int i = 0; i < history.size(); i++) {
            var c = history.get(i);
            if (!validCandle(c)) return invalidAtr();
            double tr = Math.max(c.high() - c.low(), Math.max(Math.abs(c.high() - previousClose), Math.abs(c.low() - previousClose)));
            if (i < ATR_PERIOD) atr += tr / ATR_PERIOD;
            else atr = (atr * (ATR_PERIOD - 1) + tr) / ATR_PERIOD;
            previousClose = c.close();
        }
        double risk = "PE".equalsIgnoreCase(side) ? signal.high() - signal.close() : signal.close() - signal.low();
        if (!Double.isFinite(atr) || atr <= 0 || !Double.isFinite(risk) || risk <= 0) return invalidAtr();
        if (risk - MAX_STOP_ATR * atr > 1e-9d) return new SupertrendSignalRules.Failure("ATR_STOP_WIDTH",
                "Initial spot stop " + risk + " exceeds 1.5 x five-minute ATR(14) " + atr);
        return null;
    }

    private static boolean validCandle(StrategyCandle c) {
        return Double.isFinite(c.open()) && Double.isFinite(c.high()) && Double.isFinite(c.low()) && Double.isFinite(c.close())
                && c.low() > 0 && c.low() <= Math.min(c.open(), c.close())
                && Math.max(c.open(), c.close()) <= c.high();
    }

    private static SupertrendSignalRules.Failure invalidAtr() {
        return new SupertrendSignalRules.Failure("ATR_STOP_DATA", "Waiting for valid completed five-minute ATR(14) and spot stop");
    }
}
