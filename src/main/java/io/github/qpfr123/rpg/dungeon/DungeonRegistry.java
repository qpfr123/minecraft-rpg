package io.github.qpfr123.rpg.dungeon;

import io.github.qpfr123.rpg.dungeon.DungeonDefinition.MobSpawn;
import io.github.qpfr123.rpg.dungeon.DungeonDefinition.Point;
import io.github.qpfr123.rpg.loot.LootTable;
import io.github.qpfr123.rpg.loot.LootTable.Entry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 슬라이스 2 던전 1종(시험값): 지하 납골당. 직선 회랑 + 방 3개 + 보스실. */
public final class DungeonRegistry {
    private final Map<String, DungeonDefinition> dungeons = new LinkedHashMap<>();

    public static DungeonRegistry slice2() {
        DungeonRegistry r = new DungeonRegistry();
        r.add(new DungeonDefinition("crypt", "지하 납골당", 1, 4,
                new Point(0.5, 64, 2.5, 0),
                List.of(
                        new MobSpawn("ghoul", -2.5, 64, 16.5), new MobSpawn("ghoul", 2.5, 64, 18.5), new MobSpawn("ghoul", 0.5, 64, 20.5),
                        new MobSpawn("bone_archer", -3.5, 64, 32.5), new MobSpawn("bone_archer", 3.5, 64, 32.5),
                        new MobSpawn("ghoul", -2.5, 64, 44.5), new MobSpawn("ghoul", 2.5, 64, 44.5)),
                new MobSpawn("crypt_warden", 0.5, 64, 58.5),
                new LootTable(400, List.of(new Entry("grave_knight_helm", 0.30), new Entry("shield_tonic", 1.0)))));
        return r;
    }

    private void add(DungeonDefinition d) {
        dungeons.put(d.id(), d);
    }

    public Optional<DungeonDefinition> get(String id) {
        return Optional.ofNullable(dungeons.get(id));
    }

    public Iterable<DungeonDefinition> all() {
        return dungeons.values();
    }
}
