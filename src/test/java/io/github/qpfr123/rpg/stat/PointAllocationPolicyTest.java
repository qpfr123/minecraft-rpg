package io.github.qpfr123.rpg.stat;

import org.junit.jupiter.api.Test;

import static io.github.qpfr123.rpg.stat.PointAllocationPolicy.Result.*;
import static org.junit.jupiter.api.Assertions.*;

class PointAllocationPolicyTest {
    @Test
    void grantsTenPlusThreePerLevel() {
        assertEquals(10, PointAllocationPolicy.grantedPoints(1));
        assertEquals(37, PointAllocationPolicy.grantedPoints(10));
        assertEquals(5, PointAllocationPolicy.singleStatCap(1));
        assertEquals(18, PointAllocationPolicy.singleStatCap(10));
        assertEquals(6, PointAllocationPolicy.singleStatCap(2)); // 13 * 0.5 = 6.5 → 6
    }

    @Test
    void capAppliesToInitialPoints() {
        StatAllocation a = StatAllocation.empty();
        assertEquals(OK, PointAllocationPolicy.check(a, 1, SecondaryStat.STRENGTH, 5));
        assertEquals(CAP_EXCEEDED, PointAllocationPolicy.check(a, 1, SecondaryStat.STRENGTH, 6));
        a = a.plus(SecondaryStat.STRENGTH, 5);
        assertEquals(CAP_EXCEEDED, PointAllocationPolicy.check(a, 1, SecondaryStat.STRENGTH, 1));
        assertEquals(OK, PointAllocationPolicy.check(a, 1, SecondaryStat.VITALITY, 5));
    }

    @Test
    void rejectsOverspendAndInvalidAmount() {
        StatAllocation a = StatAllocation.empty().plus(SecondaryStat.STRENGTH, 5).plus(SecondaryStat.VITALITY, 4);
        assertEquals(1, PointAllocationPolicy.unspent(a, 1));
        assertEquals(NOT_ENOUGH_POINTS, PointAllocationPolicy.check(a, 1, SecondaryStat.LUCK, 2));
        assertEquals(INVALID_AMOUNT, PointAllocationPolicy.check(a, 1, SecondaryStat.LUCK, 0));
        assertEquals(INVALID_AMOUNT, PointAllocationPolicy.check(a, 1, SecondaryStat.LUCK, -1));
    }

    @Test
    void unspentPointsStayInCapDenominator() {
        // 레벨 5: 지급 22, 상한 11. 미사용 포인트가 있어도 상한은 지급 총량 기준.
        StatAllocation a = StatAllocation.empty();
        assertEquals(OK, PointAllocationPolicy.check(a, 5, SecondaryStat.FOCUS, 11));
        assertEquals(CAP_EXCEEDED, PointAllocationPolicy.check(a, 5, SecondaryStat.FOCUS, 12));
    }
}
