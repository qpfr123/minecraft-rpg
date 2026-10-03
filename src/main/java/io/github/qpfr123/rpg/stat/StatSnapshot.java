package io.github.qpfr123.rpg.stat;

/** 행동 시점의 최종 1차 스탯. */
public record StatSnapshot(
        int maxHp,
        int maxMp,
        double hpRegen,
        double mpRegen,
        double atk,
        double def,
        double statusResist,
        double critChance,
        double critDamage,
        double attackSpeed,
        double moveSpeedMultiplier,
        double cooldownReductionSeconds,
        double lifesteal,
        double damageIncrease,
        double dropBonus,
        double expBonus) {

    public int shieldCap() {
        return (int) Math.floor(maxHp * io.github.qpfr123.rpg.config.Balance.SHIELD_CAP_RATIO);
    }
}
