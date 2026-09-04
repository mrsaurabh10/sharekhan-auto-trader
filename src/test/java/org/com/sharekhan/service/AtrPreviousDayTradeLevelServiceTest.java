package org.com.sharekhan.service;

import org.com.sharekhan.entity.TriggerTradeRequestEntity;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AtrPreviousDayTradeLevelServiceTest {

    private final AtrPreviousDayTradeLevelService service = new AtrPreviousDayTradeLevelService();

    @Test
    void recalculatesCeLevelsFromTheOriginalAtr() {
        TriggerTradeRequestEntity request = request("CE", 365.97, 363.45, 369.75, 372.27, 373.53);

        var levels = service.recalculate(request, 366.50);

        assertTrue(levels.isPresent());
        assertEquals(363.98, levels.get().stopLoss());
        assertEquals(370.28, levels.get().target1());
        assertEquals(372.80, levels.get().target2());
        assertEquals(374.06, levels.get().target3());
    }

    @Test
    void recalculatesPeLevelsInTheOppositeDirection() {
        TriggerTradeRequestEntity request = request("PE", 200, 202, 197, 195, 194);

        var levels = service.recalculate(request, 201);

        assertTrue(levels.isPresent());
        assertEquals(203, levels.get().stopLoss());
        assertEquals(198, levels.get().target1());
        assertEquals(196, levels.get().target2());
        assertEquals(195, levels.get().target3());
    }

    @Test
    void recalculatesExecutedAtrTradeLevelsWhenSpotEntryIsEdited() {
        TriggeredTradeSetupEntity trade = TriggeredTradeSetupEntity.builder()
                .source(AtrPreviousDayTradeLevelService.SOURCE).optionType("CE")
                .entryPrice(1309.10).stopLoss(1299.94)
                .target1(1322.84).target2(1331.98).target3(1336.56).build();

        var levels = service.recalculate(trade, 1299.60);

        assertTrue(levels.isPresent());
        assertEquals(1290.44, levels.get().stopLoss());
        assertEquals(1313.34, levels.get().target1());
        assertEquals(1322.50, levels.get().target2());
        assertEquals(1327.08, levels.get().target3());
    }

    private TriggerTradeRequestEntity request(String optionType, double entry, double stop, double target1,
                                              double target2, double target3) {
        return TriggerTradeRequestEntity.builder().source(AtrPreviousDayTradeLevelService.SOURCE)
                .optionType(optionType).entryPrice(entry).stopLoss(stop).target1(target1)
                .target2(target2).target3(target3).build();
    }
}
