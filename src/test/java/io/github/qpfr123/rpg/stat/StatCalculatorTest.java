package io.github.qpfr123.rpg.stat;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StatCalculatorTest {
    private static final double EPS = 1e-9;

    @Test
    void baseValuesWithoutInvestment() {
        StatSnapshot s = StatCalculator.compute(StatAllocation.empty(), StatBonuses.NONE);
        assertEquals(100, s.maxHp());
        assertEquals(100, s.maxMp());
        assertEquals(4, s.hpRegen(), EPS);
        assertEquals(10, s.atk(), EPS);
        assertEquals(0, s.def(), EPS);
        assertEquals(0.10, s.critChance(), EPS);
        assertEquals(0.50, s.critDamage(), EPS);
        assertEquals(1.0, s.attackSpeed(), EPS);
        assertEquals(50, s.shieldCap());
    }

    @Test
    void secondaryCoefficientsMatchDesign() {
        StatAllocation a = StatAllocation.of(Map.of(
                SecondaryStat.STRENGTH, 10, SecondaryStat.VITALITY, 10, SecondaryStat.SPIRIT, 10,
                SecondaryStat.RESISTANCE, 10, SecondaryStat.FOCUS, 10, SecondaryStat.AGILITY, 10, SecondaryStat.LUCK, 10));
        StatSnapshot s = StatCalculator.compute(a, StatBonuses.builder().flatAtk(5).flatHp(50).build());
        assertEquals(15 * 1.06, s.atk(), EPS);                // (10+5)×(1+0.006×10)
        assertEquals((int) Math.floor(150 * 1.06), s.maxHp()); // 159
        assertEquals(106, s.maxMp());
        assertEquals(5, s.hpRegen(), EPS);
        assertEquals(5, s.mpRegen(), EPS);
        assertEquals(0.02, s.def(), EPS);
        assertEquals(0.02, s.statusResist(), EPS);
        assertEquals(0.16, s.critChance(), EPS);
        assertEquals(0.60, s.critDamage(), EPS);
        assertEquals(1.06, s.attackSpeed(), EPS);
        assertEquals(1.04, s.moveSpeedMultiplier(), EPS);
        assertEquals(0.04, s.dropBonus(), EPS);
    }

    @Test
    void capsAreEnforced() {
        StatBonuses huge = StatBonuses.builder().def(5).statusResist(5).critChance(5).critDamage(50).attackSpeed(50)
                .cooldownReductionSeconds(99).lifesteal(5).dropBonus(50).expBonus(50).build();
        StatSnapshot s = StatCalculator.compute(StatAllocation.empty(), huge);
        assertEquals(0.50, s.def(), EPS);
        assertEquals(0.70, s.statusResist(), EPS);
        assertEquals(1.0, s.critChance(), EPS);
        assertEquals(4.0, s.critDamage(), EPS);
        assertEquals(3.0, s.attackSpeed(), EPS);
        assertEquals(10, s.cooldownReductionSeconds(), EPS);
        assertEquals(0.20, s.lifesteal(), EPS);
        assertEquals(2.0, s.dropBonus(), EPS);
        assertEquals(5.0, s.expBonus(), EPS);
    }
}
