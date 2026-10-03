package io.github.qpfr123.rpg.combat;

import io.github.qpfr123.rpg.config.Balance;

public final class RegenCalculator {
    private RegenCalculator() {}

    /** @return 회복 후 값. 최대치를 넘지 않는다. */
    public static double regen(double current, int max, double perSecond, double seconds, boolean inCombat) {
        if (current <= 0) return current; // 사망 상태는 재생하지 않음
        double rate = inCombat ? Balance.COMBAT_REGEN_RATIO : 1.0;
        return Math.min(max, current + perSecond * rate * seconds);
    }
}
