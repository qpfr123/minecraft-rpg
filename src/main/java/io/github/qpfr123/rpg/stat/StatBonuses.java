package io.github.qpfr123.rpg.stat;

/**
 * 장비·버프가 주는 고정/비율 보너스. 퍼센트는 0.10 = 10% 형태.
 */
public record StatBonuses(
        double flatHp,
        double flatMp,
        double flatAtk,
        double def,
        double statusResist,
        double critChance,
        double critDamage,
        double attackSpeed,
        double cooldownReductionSeconds,
        double lifesteal,
        double hpIncrease,
        double damageIncrease,
        double hpRegen,
        double mpRegen,
        double dropBonus,
        double expBonus) {

    public static final StatBonuses NONE = new StatBonuses(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

    public StatBonuses plus(StatBonuses o) {
        return new StatBonuses(
                flatHp + o.flatHp, flatMp + o.flatMp, flatAtk + o.flatAtk, def + o.def,
                statusResist + o.statusResist, critChance + o.critChance, critDamage + o.critDamage,
                attackSpeed + o.attackSpeed, cooldownReductionSeconds + o.cooldownReductionSeconds,
                lifesteal + o.lifesteal, hpIncrease + o.hpIncrease, damageIncrease + o.damageIncrease,
                hpRegen + o.hpRegen, mpRegen + o.mpRegen, dropBonus + o.dropBonus, expBonus + o.expBonus);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private double flatHp, flatMp, flatAtk, def, statusResist, critChance, critDamage, attackSpeed,
                cooldownReductionSeconds, lifesteal, hpIncrease, damageIncrease, hpRegen, mpRegen, dropBonus, expBonus;

        public Builder flatHp(double v) { flatHp = v; return this; }
        public Builder flatMp(double v) { flatMp = v; return this; }
        public Builder flatAtk(double v) { flatAtk = v; return this; }
        public Builder def(double v) { def = v; return this; }
        public Builder statusResist(double v) { statusResist = v; return this; }
        public Builder critChance(double v) { critChance = v; return this; }
        public Builder critDamage(double v) { critDamage = v; return this; }
        public Builder attackSpeed(double v) { attackSpeed = v; return this; }
        public Builder cooldownReductionSeconds(double v) { cooldownReductionSeconds = v; return this; }
        public Builder lifesteal(double v) { lifesteal = v; return this; }
        public Builder hpIncrease(double v) { hpIncrease = v; return this; }
        public Builder damageIncrease(double v) { damageIncrease = v; return this; }
        public Builder hpRegen(double v) { hpRegen = v; return this; }
        public Builder mpRegen(double v) { mpRegen = v; return this; }
        public Builder dropBonus(double v) { dropBonus = v; return this; }
        public Builder expBonus(double v) { expBonus = v; return this; }

        public StatBonuses build() {
            return new StatBonuses(flatHp, flatMp, flatAtk, def, statusResist, critChance, critDamage, attackSpeed,
                    cooldownReductionSeconds, lifesteal, hpIncrease, damageIncrease, hpRegen, mpRegen, dropBonus, expBonus);
        }
    }
}
