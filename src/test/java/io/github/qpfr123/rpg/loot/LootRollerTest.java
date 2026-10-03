package io.github.qpfr123.rpg.loot;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LootRollerTest {
    @Test
    void dropBonusMultipliesBaseChanceAndCapsAtOne() {
        assertEquals(0.12, LootRoller.effectiveChance(0.10, 0.2), 1e-9);
        assertEquals(1.0, LootRoller.effectiveChance(0.6, 1.0), 1e-9);
    }

    @Test
    void rollsEachEntryIndependently() {
        LootTable t = new LootTable(10, List.of(new LootTable.Entry("a", 0.5), new LootTable.Entry("b", 0.1)));
        assertEquals(List.of("a"), new LootRoller(() -> 0.2).roll(t, 0));
        assertEquals(List.of("a", "b"), new LootRoller(() -> 0.05).roll(t, 0));
        assertEquals(List.of(), new LootRoller(() -> 0.6).roll(t, 0));
    }
}
