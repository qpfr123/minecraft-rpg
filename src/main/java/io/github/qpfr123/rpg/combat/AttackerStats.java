package io.github.qpfr123.rpg.combat;

import io.github.qpfr123.rpg.stat.StatSnapshot;

/** RPG 공격 피해 계산에 필요한 공격자 수치. */
public record AttackerStats(double atk, double damageIncrease, double critChance, double critDamage, double lifesteal) {
    public static AttackerStats of(StatSnapshot s) {
        return new AttackerStats(s.atk(), s.damageIncrease(), s.critChance(), s.critDamage(), s.lifesteal());
    }

    /** 치명타·흡수 없는 단순 공격자(몹, 태그 없는 바닐라 몹). */
    public static AttackerStats flat(double atk) {
        return new AttackerStats(atk, 0, 0, 0, 0);
    }
}
