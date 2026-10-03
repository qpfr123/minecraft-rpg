package io.github.qpfr123.rpg.profile;

import io.github.qpfr123.rpg.stat.PointAllocationPolicy;
import io.github.qpfr123.rpg.stat.SecondaryStat;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ProfileTest {
    @Test
    void levelUpRepeatsAndStopsAtMaxLevel() {
        assertEquals(100, ExperienceCurve.required(1));
        assertEquals(283, ExperienceCurve.required(2));
        ExperienceCurve.Progress p = ExperienceCurve.add(1, 0, 100 + 283 + 10);
        assertEquals(3, p.level());
        assertEquals(10, p.exp());
        assertEquals(2, p.levelsGained());
        ExperienceCurve.Progress capped = ExperienceCurve.add(9, 0, 1_000_000);
        assertEquals(10, capped.level());
        assertEquals(0, capped.exp());
        assertEquals(0, ExperienceCurve.add(10, 0, 500).exp());
    }

    @Test
    void levelUpGrantsMorePoints() {
        PlayerProfile p = PlayerProfile.fresh(UUID.randomUUID());
        assertEquals(PointAllocationPolicy.Result.OK, p.allocate(SecondaryStat.STRENGTH, 5));
        assertEquals(PointAllocationPolicy.Result.CAP_EXCEEDED, p.allocate(SecondaryStat.STRENGTH, 1));
        p.addExp(100);
        assertEquals(2, p.level());
        assertEquals(8, p.unspentPoints());
        assertEquals(PointAllocationPolicy.Result.OK, p.allocate(SecondaryStat.STRENGTH, 1)); // 상한 6
        assertEquals(PointAllocationPolicy.Result.CAP_EXCEEDED, p.allocate(SecondaryStat.STRENGTH, 1));
    }

    @Test
    void clampDoesNotHealForFree() {
        PlayerProfile p = PlayerProfile.fresh(UUID.randomUUID());
        p.setHp(40);
        p.setShield(60);
        p.clampTo(150, 100, 75);
        assertEquals(40, p.hp());
        assertEquals(60, p.shield());
        p.clampTo(30, 100, 15);
        assertEquals(30, p.hp());
        assertEquals(15, p.shield());
    }

    @Test
    void adminCanOnlyRaiseLevel() {
        PlayerProfile p = PlayerProfile.fresh(UUID.randomUUID());
        assertTrue(p.raiseLevelForAdmin(5));
        assertFalse(p.raiseLevelForAdmin(3));
        assertFalse(p.raiseLevelForAdmin(11));
    }
}
