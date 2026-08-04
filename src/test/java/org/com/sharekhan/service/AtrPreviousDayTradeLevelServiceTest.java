package org.com.sharekhan.service;

import org.com.sharekhan.entity.TriggerTradeRequestEntity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AtrPreviousDayTradeLevelServiceTest {

    private final AtrPreviousDayTradeLevelService service = new AtrPreviousDayTradeLevelService();

    @Test
    void recalculatesCeLevelsFromTheOriginalAtr() {
        TriggerTradeRequestEntity request = request("CE", 365.97, 363.45, 368.49, 369.75, 371.01);

        var levels = service.recalculate(request, 366.50);

        assertTrue(levels.isPresent());
        assertEquals(363.98, levels.get().stopLoss());
        assertEquals(369.02, levels.get().target1());
        assertEquals(370.28, levels.get().target2());
        assertEquals(371.54, levels.get().target3());
    }

    @Test
    void recalculatesPeLevelsInTheOppositeDirection() {
        TriggerTradeRequestEntity request = request("PE", 200, 202, 198, 197, 196);

        var levels = service.recalculate(request, 201);

        assertTrue(levels.isPresent());
        assertEquals(203, levels.get().stopLoss());
        assertEquals(199, levels.get().target1());
        assertEquals(198, levels.get().target2());
        assertEquals(197, levels.get().target3());
    }

    private TriggerTradeRequestEntity request(String optionType, double entry, double stop, double target1,
                                              double target2, double target3) {
        return TriggerTradeRequestEntity.builder().source(AtrPreviousDayTradeLevelService.SOURCE)
                .optionType(optionType).entryPrice(entry).stopLoss(stop).target1(target1)
                .target2(target2).target3(target3).build();
    }
}
