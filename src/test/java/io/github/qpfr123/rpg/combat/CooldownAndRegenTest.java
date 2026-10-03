package io.github.qpfr123.rpg.combat;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CooldownAndRegenTest {
    @Test
    void skillCooldownFloor() {
        assertEquals(3, CooldownPolicy.skillCooldownSeconds(3, 10), 1e-9);   // 3초는 늘어나지 않음
        assertEquals(5, CooldownPolicy.skillCooldownSeconds(5, 10), 1e-9);
        assertEquals(5, CooldownPolicy.skillCooldownSeconds(12, 10), 1e-9);  // 바닥 5초
        assertEquals(20, CooldownPolicy.skillCooldownSeconds(30, 10), 1e-9);
        assertEquals(30, CooldownPolicy.skillCooldownSeconds(30, 0), 1e-9);
    }

    @Test
    void basicAttackIntervalScalesWithAttackSpeed() {
        assertEquals(600, CooldownPolicy.basicAttackIntervalMillis(1.0));
        assertEquals(200, CooldownPolicy.basicAttackIntervalMillis(3.0));
        assertEquals(60_000, CooldownPolicy.basicAttackIntervalMillis(0));
    }

    @Test
    void regenIsReducedInCombatAndCapped() {
        assertEquals(54, RegenCalculator.regen(50, 100, 4, 1, false), 1e-9);
        assertEquals(50.8, RegenCalculator.regen(50, 100, 4, 1, true), 1e-9);
        assertEquals(100, RegenCalculator.regen(99, 100, 4, 1, false), 1e-9);
        assertEquals(0, RegenCalculator.regen(0, 100, 4, 1, false), 1e-9); // 사망 상태
    }

    @Test
    void combatStateTimesOut() {
        CombatStateTracker t = new CombatStateTracker();
        UUID id = UUID.randomUUID();
        assertFalse(t.inCombat(id, 0));
        t.markHostile(id, 1_000);
        assertTrue(t.inCombat(id, 5_999));
        assertFalse(t.inCombat(id, 6_000));
    }
}
