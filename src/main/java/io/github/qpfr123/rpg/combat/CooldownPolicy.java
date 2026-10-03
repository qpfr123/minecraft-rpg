package io.github.qpfr123.rpg.combat;

import io.github.qpfr123.rpg.config.Balance;

public final class CooldownPolicy {
    private CooldownPolicy() {}

    /** 기본 스킬 간격 = 기본 간격 / max(공격속도, 0.01). */
    public static long basicAttackIntervalMillis(double attackSpeed) {
        return Math.round(Balance.BASIC_ATTACK_INTERVAL_MILLIS / Math.max(attackSpeed, 0.01));
    }

    /** 기본 스킬 외: max(min(원래, 5초), 원래 - 감소초). 원래 쿨타임이 5초 미만이면 늘어나지 않는다. */
    public static double skillCooldownSeconds(double original, double reductionSeconds) {
        double floor = Math.min(original, Balance.SKILL_COOLDOWN_FLOOR_SECONDS);
        return Math.max(floor, original - Math.max(0, reductionSeconds));
    }
}
