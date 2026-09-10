package org.com.sharekhan.strategy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.dto.StrategyApplyRequest;
import org.com.sharekhan.dto.StrategyApplyResponse;
import org.com.sharekhan.dto.TriggerRequest;
import org.com.sharekhan.entity.MarketauxEntitySentimentEntity;
import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.entity.TriggerTradeRequestEntity;
import org.com.sharekhan.repository.MarketauxEntitySentimentRepository;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** A direct corporate catalyst selects direction; a post-news price breakout confirms entry. */
@Component
@RequiredArgsConstructor
@Slf4j
public class MarketauxSentimentSwingAtrStrategy implements StrategyEvaluator {

    public static final String TEMPLATE_ID = "MARKETAUX_SENTIMENT_SWING_ATR";
    public static final String SOURCE = "marketaux-sentiment-swing-strategy";
    static final double SENTIMENT_THRESHOLD = 0.30d;
    private static final int MAX_NEWS_AGE_MINUTES = 90;
    private static final LocalTime ENTRY_CUTOFF = LocalTime.of(14, 30);
    private static final List<String> CATALYST_TERMS = List.of(
            "order", "contract", "deal", "results", "guidance", "upgrade", "downgrade", "target price",
            "stake sale", "block deal", "qip", "approval", "approved", "regulatory", "acquisition",
            "merger", "buyback", "dividend", "wins", "win", "secures", "secure", "launches", "launch",
            "raises", "raise", "cuts", "cut", "falls", "fall", "drops", "drop", "slips", "slip",
            "warning", "ban", "recall", "probe", "penalty", "fine", "fraud");
    private static final int DEFAULT_LOTS = 1;
    private static final StrategyMetadata METADATA = new StrategyMetadata(
            TEMPLATE_ID, "Marketaux Sentiment Swing ATR",
            "For F&O stocks with a direct corporate-catalyst headline and Marketaux score at least +/-0.30, confirms a post-news five-minute swing break with 1.5x relative volume. A breakout buys ATM CE; a breakdown buys ATM PE. Spot stop is 2 ATR and the sole spot target is 3 ATR.",
            "AUTO");

    private final MarketauxEntitySentimentRepository sentimentRepository;
    private final StrategySupport support;
    private final MarketauxNewsSwingQualificationService qualificationService;

    @Override public StrategyMetadata metadata() { return METADATA; }

    @Override
    public StrategyApplyResponse apply(StrategyApplyRequest request) {
        LocalDateTime now = LocalDateTime.now(StrategySupport.MARKET_ZONE);
        if (now.toLocalTime().isAfter(ENTRY_CUTOFF)) {
            return support.waiting(METADATA, "FNO_UNIVERSE", "New Marketaux sentiment entries stop after " + ENTRY_CUTOFF + " IST.");
        }
        List<Candidate> candidates = candidatesFor(now);
        if (candidates.isEmpty()) {
            return support.waiting(METADATA, "FNO_UNIVERSE",
                    "No recent direct F&O corporate-catalyst article has a Marketaux sentiment score beyond +/- " + SENTIMENT_THRESHOLD + ".");
        }
        List<String> waiting = new java.util.ArrayList<>();
        List<Created> created = new java.util.ArrayList<>();
        for (Candidate candidate : candidates) {
            try {
                ScriptMasterEntity spot = support.resolveSpotScript(candidate.symbol());
                support.mstockAvailabilityFailure(spot).ifPresentOrElse(
                        failure -> waiting.add(candidate.symbol() + ": " + failure),
                        () -> createIfQualified(request, now, candidate, spot, created, waiting));
            } catch (Exception ex) {
                waiting.add(candidate.symbol() + ": " + ex.getMessage());
            }
        }
        if (created.isEmpty()) {
            return support.waiting(METADATA, "FNO_UNIVERSE",
                    "Sentiment candidates are waiting for post-news swing-break and volume confirmation: " + String.join("; ", waiting));
        }
        Created first = created.get(0);
        long newRequests = created.stream().filter(item -> !item.duplicate()).count();
        return StrategyApplyResponse.builder()
                .status(newRequests > 0 ? "triggered" : "duplicate")
                .message("Created " + newRequests + " Marketaux sentiment swing request(s): "
                        + created.stream().map(Created::summary).toList()
                        + (waiting.isEmpty() ? "" : ". Waiting: " + String.join("; ", waiting)))
                .templateId(TEMPLATE_ID).symbol(first.candidate().symbol()).direction(first.candidate().optionType())
                .breakoutClose(support.roundPrice(first.trigger().getEntryPrice()))
                .triggerRequest(first.trigger()).tradeRequest(first.request()).build();
    }

    private void createIfQualified(StrategyApplyRequest request, LocalDateTime now, Candidate candidate,
                                   ScriptMasterEntity spot, List<Created> created, List<String> waiting) {
        if (support.hasEntryForSymbolOn(SOURCE, now.toLocalDate(), request.getUserId(), candidate.symbol())) {
            waiting.add(candidate.symbol() + ": a Marketaux sentiment entry already occurred today");
            return;
        }
        StrategyMetadata directionMetadata = metadataFor(candidate.optionType());
        support.warmUpPreferredFnoFeeds(request, directionMetadata, candidate.symbol(), spot);
        Fno925EntryQualificationService.Qualification qualification = qualificationService.qualify(
                spot, candidate.optionType(), candidate.newsObservedAt(), now);
        if (!qualification.qualified()) {
            waiting.add(candidate.symbol() + ": " + qualification.reason());
            return;
        }
        TriggerRequest trigger = buildTrigger(request, candidate, spot, qualification.signal());
        TriggerTradeRequestEntity existing = support.findActiveSetup(trigger, SOURCE);
        TriggerTradeRequestEntity trade = existing != null ? existing : support.createPendingTradeRequest(trigger);
        support.auditTradeRequest(request, directionMetadata, candidate.symbol(), existing == null ? "CREATED" : "DUPLICATE", trigger, trade);
        created.add(new Created(candidate, trigger, trade, existing != null));
        log.info("MARKETAUX_SENTIMENT_SWING | symbol={} | score={} | direction={} | setup={} | entry={} | stop={} | target={}",
                candidate.symbol(), candidate.score(), candidate.optionType(), qualification.signal().setup(),
                support.roundPrice(trigger.getEntryPrice()), support.roundPrice(trigger.getStopLoss()), support.roundPrice(trigger.getTarget1()));
    }

    private TriggerRequest buildTrigger(StrategyApplyRequest request, Candidate candidate, ScriptMasterEntity spot,
                                        Fno925EntryQualificationService.Signal signal) {
        boolean ce = "CE".equals(candidate.optionType());
        double entry = signal.entryPrice(), atr = signal.atr();
        String expiry = support.preferredFnoExpiry(candidate.symbol(), candidate.optionType());
        StrategySupport.FnoOptionContract contract = support.resolveFnoEntryContract(request, metadataFor(candidate.optionType()),
                candidate.symbol(), expiry, support.nearestStrike(candidate.symbol(), candidate.optionType(), expiry, entry));
        TriggerRequest trigger = new TriggerRequest();
        trigger.setInstrument(candidate.symbol()); trigger.setEntryPrice(support.roundPrice(entry));
        trigger.setStopLoss(support.roundPrice(ce ? entry - 2d * atr : entry + 2d * atr));
        trigger.setTarget1(support.roundPrice(ce ? entry + 3d * atr : entry - 3d * atr));
        trigger.setTarget2(null); trigger.setTarget3(null); // One complete 3-ATR exit, not staged profit-taking.
        trigger.setOptionType(candidate.optionType()); trigger.setExpiry(contract.expiry()); trigger.setStrikePrice(contract.strike());
        trigger.setIntraday(request.getIntraday() == null || request.getIntraday());
        trigger.setUseSpotPrice(true); trigger.setUseSpotForEntry(true); trigger.setUseSpotForSl(true); trigger.setUseSpotForTarget(true);
        trigger.setSpotScripCode(spot.getScripCode()); trigger.setUserId(request.getUserId());
        trigger.setBrokerCredentialsId(request.getBrokerCredentialsId()); trigger.setSource(SOURCE);
        int lots = request.getLots() != null && request.getLots() > 0 ? request.getLots() : DEFAULT_LOTS;
        trigger.setLots(lots); trigger.setQuantity(lots); trigger.setTslEnabled(false);
        return trigger;
    }

    private List<Candidate> candidatesFor(LocalDateTime now) {
        Map<String, Candidate> strongestBySymbol = new LinkedHashMap<>();
        for (MarketauxEntitySentimentEntity row : sentimentRepository.findTop500ByTradingDateOrderByCollectedAtDesc(now.toLocalDate())) {
            if (!eligible(row, now)) continue;
            Candidate candidate = new Candidate(row.getEntitySymbol().trim().toUpperCase(Locale.ROOT), row.getSentimentScore(),
                    row.getSentimentScore() > 0d ? "CE" : "PE", row.getCollectedAt());
            strongestBySymbol.merge(candidate.symbol(), candidate,
                    (left, right) -> Math.abs(left.score()) >= Math.abs(right.score()) ? left : right);
        }
        return strongestBySymbol.values().stream()
                .sorted(Comparator.comparingDouble((Candidate item) -> Math.abs(item.score())).reversed()).toList();
    }

    private boolean eligible(MarketauxEntitySentimentEntity row, LocalDateTime now) {
        if (row == null || row.getSentimentScore() == null || !Double.isFinite(row.getSentimentScore())
                || Math.abs(row.getSentimentScore()) < SENTIMENT_THRESHOLD || Boolean.TRUE.equals(row.getBroadMarketArticle())
                || !StringUtils.hasText(row.getEntitySymbol()) || !StringUtils.hasText(row.getEntityName())
                || !StringUtils.hasText(row.getArticleTitle()) || row.getCollectedAt() == null
                || row.getCollectedAt().isBefore(now.minusMinutes(MAX_NEWS_AGE_MINUTES)) || row.getCollectedAt().isAfter(now)) return false;
        String name = row.getEntityName().toUpperCase(Locale.ROOT)
                .replaceAll("\\b(LIMITED|LTD|INCORPORATED|INC)\\b", "").replaceAll("\\s+", " ").trim();
        String title = row.getArticleTitle().toUpperCase(Locale.ROOT);
        int nameIndex = title.indexOf(name);
        boolean nameLeadsHeadline = nameIndex >= 0 && nameIndex <= title.length() * 0.45d;
        boolean hasCatalyst = CATALYST_TERMS.stream().anyMatch(title::contains);
        return name.length() >= 3 && nameLeadsHeadline && hasCatalyst;
    }

    private StrategyMetadata metadataFor(String optionType) {
        return new StrategyMetadata(TEMPLATE_ID, METADATA.name(), METADATA.description(), optionType);
    }

    private record Candidate(String symbol, double score, String optionType, LocalDateTime newsObservedAt) { }
    private record Created(Candidate candidate, TriggerRequest trigger, TriggerTradeRequestEntity request, boolean duplicate) {
        String summary() { return candidate.symbol() + " " + candidate.optionType() + " (score=" + candidate.score() + ")"; }
    }
}
