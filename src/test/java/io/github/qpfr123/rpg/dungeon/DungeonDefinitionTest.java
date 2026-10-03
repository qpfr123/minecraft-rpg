package io.github.qpfr123.rpg.dungeon;

import io.github.qpfr123.rpg.dungeon.DungeonDefinition.MobSpawn;
import io.github.qpfr123.rpg.dungeon.DungeonDefinition.Point;
import io.github.qpfr123.rpg.loot.LootTable;
import io.github.qpfr123.rpg.mob.MobRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DungeonDefinitionTest {
    private final MobRegistry mobs = MobRegistry.slice1();

    private DungeonDefinition draft() {
        return new DungeonDefinition("test_lab", "실험실", null, 1, 4, new Point(0.5, 64, 0.5, 0), List.of(), null,
                new LootTable(200, List.of()));
    }

    @Test
    void defaultCryptIsValidAndHasExpectedMobs() {
        DungeonDefinition c = DungeonRegistry.defaultCrypt();
        assertEquals(List.of(), DungeonValidator.validate(c, mobs));
        assertEquals(6, c.spawns().stream().filter(s -> s.mobId().equals("ghoul")).count());
        assertEquals(3, c.spawns().stream().filter(s -> s.mobId().equals("bone_archer")).count());
        assertEquals("crypt_warden", c.boss().mobId());
    }

    @Test
    void draftWithoutBossIsRejected() {
        List<String> e = DungeonValidator.validate(draft(), mobs);
        assertEquals(1, e.size());
        assertTrue(e.getFirst().contains("보스"));
        assertEquals(List.of(), DungeonValidator.validate(draft().withBoss(new MobSpawn("crypt_warden", 0, 64, 10)), mobs));
    }

    @Test
    void rejectsUnknownMobsBossInSpawnsAndBadIds() {
        DungeonDefinition d = draft().withBoss(new MobSpawn("ghoul", 0, 64, 10))
                .plusSpawn(new MobSpawn("dragon", 0, 64, 5)).plusSpawn(new MobSpawn("grave_knight", 0, 64, 6));
        List<String> e = DungeonValidator.validate(d, mobs);
        assertEquals(3, e.size(), e.toString());
        DungeonDefinition bad = new DungeonDefinition("Bad-Id", "x", null, 1, 9, null, List.of(), null, null);
        assertTrue(DungeonValidator.validate(bad, mobs).size() >= 4);
    }

    @Test
    void removeNearestSpawnWithinRadius() {
        DungeonDefinition d = draft().plusSpawn(new MobSpawn("ghoul", 0, 64, 5)).plusSpawn(new MobSpawn("ghoul", 0, 64, 9));
        assertNull(d.minusNearestSpawn(0, 64, 20, 3));
        DungeonDefinition after = d.minusNearestSpawn(0, 64, 8, 3);
        assertEquals(List.of(new MobSpawn("ghoul", 0, 64, 5)), after.spawns());
    }

    @Test
    void handBuiltCopyDropsGenerator() {
        assertNull(DungeonRegistry.defaultCrypt().asHandBuilt().generator());
    }
}
