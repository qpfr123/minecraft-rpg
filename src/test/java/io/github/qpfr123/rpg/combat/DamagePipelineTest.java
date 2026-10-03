package io.github.qpfr123.rpg.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DamagePipelineTest {
    private static final double EPS = 1e-9;
    private final DamagePipeline alwaysCrit = new DamagePipeline(() -> 0.0);
    private final DamagePipeline neverCrit = new DamagePipeline(() -> 0.999);

    @Test
    void orderIsAtkThenIncreaseThenCritThenDefThenShieldThenHpThenLifesteal() {
        AttackerStats atk = new AttackerStats(10, 0.2, 0.5, 0.5, 0.1);
        DefenderState t = new DefenderState(100, 5, 100, 0.25);
        DamageResult r = alwaysCrit.apply(new DamageRequest.Attack(atk, 2.0), t);
        // 10×2 = 20 → ×1.2 = 24 → 치명 ×1.5 = 36 → DEF 25% = 27 → 방어막 5 → HP 22 → 흡수 2.2
        assertTrue(r.critical());
        assertEquals(36, r.preMitigation(), EPS);
        assertEquals(5, r.shieldDamage(), EPS);
        assertEquals(22, r.hpDamage(), EPS);
        assertEquals(78, r.newHp(), EPS);
        assertEquals(0, r.newShield(), EPS);
        assertEquals(2.2, r.attackerHeal(), EPS);
    }

    @Test
    void noCritWhenRollFails() {
        DamageResult r = neverCrit.apply(new DamageRequest.Attack(new AttackerStats(10, 0, 0.5, 0.5, 0), 1), new DefenderState(100, 0, 100, 0));
        assertFalse(r.critical());
        assertEquals(10, r.hpDamage(), EPS);
    }

    @Test
    void lifestealExcludesShieldAndOverkill() {
        AttackerStats atk = new AttackerStats(100, 0, 0, 0, 0.2);
        DamageResult r = neverCrit.apply(new DamageRequest.Attack(atk, 1), new DefenderState(30, 20, 100, 0));
        assertEquals(20, r.shieldDamage(), EPS);
        assertEquals(30, r.hpDamage(), EPS); // 남은 80 중 HP 30만 실제 피해
        assertEquals(6, r.attackerHeal(), EPS);
        assertTrue(r.lethal());
    }

    @Test
    void shieldPartiallyAbsorbs() {
        DamageResult r = neverCrit.apply(new DamageRequest.Attack(AttackerStats.flat(8), 1), new DefenderState(100, 20, 100, 0));
        assertEquals(8, r.shieldDamage(), EPS);
        assertEquals(0, r.hpDamage(), EPS);
        assertEquals(12, r.newShield(), EPS);
    }

    @Test
    void defIsClampedToFiftyPercent() {
        DamageResult r = neverCrit.apply(new DamageRequest.Attack(AttackerStats.flat(10), 1), new DefenderState(100, 0, 100, 0.9));
        assertEquals(5, r.hpDamage(), EPS);
    }

    @Test
    void environmentScalesWithMaxHpAndIgnoresDefAndShield() {
        DamageResult r = alwaysCrit.apply(new DamageRequest.Environment(4), new DefenderState(200, 50, 200, 0.5));
        assertEquals(40, r.hpDamage(), EPS); // 4/20 × 200
        assertEquals(50, r.newShield(), EPS);
        assertFalse(r.critical());
    }

    @Test
    void trueKillBypassesShield() {
        DamageResult r = neverCrit.apply(new DamageRequest.TrueKill(), new DefenderState(80, 40, 100, 0.5));
        assertTrue(r.lethal());
        assertEquals(40, r.newShield(), EPS);
    }

    @Test
    void displayHpNeverShowsZeroWhileAlive() {
        assertEquals(1, DamagePipeline.displayHp(0.01));
        assertEquals(0, DamagePipeline.displayHp(0));
        assertEquals(0, DamagePipeline.displayHp(-3));
        assertEquals(57, DamagePipeline.displayHp(56.2));
    }
}
