package io.github.qpfr123.rpg.stat;

import static io.github.qpfr123.rpg.config.Balance.*;

/** design.md 1차 스탯 레지스트리의 초기 구현식. 고정값 합산 → 비율 적용 → 상·하한 순서. */
public final class StatCalculator {
    private StatCalculator() {}

    public static StatSnapshot compute(StatAllocation a, StatBonuses b) {
        int str = a.get(SecondaryStat.STRENGTH);
        int agi = a.get(SecondaryStat.AGILITY);
        int res = a.get(SecondaryStat.RESISTANCE);
        int vit = a.get(SecondaryStat.VITALITY);
        int foc = a.get(SecondaryStat.FOCUS);
        int luk = a.get(SecondaryStat.LUCK);
        int spi = a.get(SecondaryStat.SPIRIT);

        int maxHp = Math.max(1, (int) Math.floor((BASE_HP + b.flatHp()) * (1 + VIT_HP_PCT * vit + Math.max(0, b.hpIncrease()))));
        int maxMp = Math.max(1, (int) Math.floor((BASE_MP + b.flatMp()) * (1 + SPI_MP_PCT * spi)));
        double hpRegen = Math.max(0, BASE_REGEN + b.hpRegen() + VIT_REGEN * vit);
        double mpRegen = Math.max(0, BASE_REGEN + b.mpRegen() + SPI_REGEN * spi);
        double atk = Math.max(0, (BASE_ATK + b.flatAtk()) * (1 + STR_ATK_PCT * str));
        double def = clamp(b.def() + RES_DEF * res, 0, DEF_CAP);
        double status = clamp(b.statusResist() + RES_STATUS * res, 0, STATUS_RESIST_CAP);
        double critChance = clamp(BASE_CRIT_CHANCE + b.critChance() + FOC_CRIT_CHANCE * foc, 0, CRIT_CHANCE_CAP);
        double critDamage = clamp(BASE_CRIT_DAMAGE + b.critDamage() + FOC_CRIT_DAMAGE * foc, 0, CRIT_DAMAGE_CAP);
        double attackSpeed = clamp(BASE_ATTACK_SPEED + b.attackSpeed() + AGI_ATTACK_SPEED * agi, 0, ATTACK_SPEED_CAP);
        double move = 1 + AGI_MOVE_SPEED * agi;
        double cdr = clamp(b.cooldownReductionSeconds(), 0, COOLDOWN_REDUCTION_CAP_SECONDS);
        double lifesteal = clamp(b.lifesteal(), 0, LIFESTEAL_CAP);
        double damageIncrease = Math.max(0, b.damageIncrease());
        double drop = clamp(b.dropBonus() + LUK_DROP * luk, 0, DROP_BONUS_CAP);
        double exp = clamp(b.expBonus(), 0, EXP_BONUS_CAP);
        return new StatSnapshot(maxHp, maxMp, hpRegen, mpRegen, atk, def, status, critChance, critDamage,
                attackSpeed, move, cdr, lifesteal, damageIncrease, drop, exp);
    }

    static double clamp(double v, double lo, double hi) {
        if (Double.isNaN(v)) return lo;
        return Math.max(lo, Math.min(hi, v));
    }
}
