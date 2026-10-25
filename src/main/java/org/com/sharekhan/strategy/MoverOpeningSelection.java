package org.com.sharekhan.strategy;

import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.repository.ScriptMasterRepository;
import org.com.sharekhan.service.MStockGainerLoserService;
import java.util.*;

/** Both execution strategies share one frozen underlying shortlist and direction. */
@Slf4j
final class MoverOpeningSelection {
    private static final int TOP_COUNT = 5;
    private MoverOpeningSelection() { }
    static List<Fno925Candidate> capture(ScriptMasterRepository scriptMasterRepository,
            MStockGainerLoserService gainerLoserService, StrategySupport support) {
        Set<String> fnoUniverse = new HashSet<>(scriptMasterRepository.findDistinctOptionUnderlyingSymbols());
        List<MStockGainerLoserService.Mover> gainers = gainerLoserService.topGainers().stream()
                .filter(mover -> fnoUniverse.contains(mover.symbol()))
                .toList();
        List<MStockGainerLoserService.Mover> losers = gainerLoserService.topLosers().stream()
                .filter(mover -> fnoUniverse.contains(mover.symbol()))
                .toList();
        boolean chooseGainers = gainers.size() > losers.size();
        List<MStockGainerLoserService.Mover> chosen = (chooseGainers ? gainers : losers).stream()
                .sorted(chooseGainers
                        ? Comparator.comparingDouble(MStockGainerLoserService.Mover::changePercent).reversed()
                        : Comparator.comparingDouble(MStockGainerLoserService.Mover::changePercent))
                .limit(TOP_COUNT)
                .toList();
        String optionType = chooseGainers ? "CE" : "PE";
        List<Fno925Candidate> selections = new ArrayList<>();
        for (MStockGainerLoserService.Mover mover : chosen) {
            try {
                ScriptMasterEntity spot = support.resolveSpotScript(mover.symbol());
                selections.add(new Fno925Candidate(mover.symbol(), spot, optionType));
            } catch (Exception e) {
                log.debug("Skipping F&O mover candidate {}: {}", mover.symbol(), e.getMessage());
            }
        }
        log.info("F&O 09:25 MStock snapshot: fnoGainers={}, fnoLosers={}, selectedSide={}, selected={}",
                gainers.size(), losers.size(), optionType, selections.stream().map(Fno925Candidate::symbol).toList());
        return selections;
    }

}
