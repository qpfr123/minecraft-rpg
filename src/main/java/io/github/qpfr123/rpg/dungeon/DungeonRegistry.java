package io.github.qpfr123.rpg.dungeon;

import io.github.qpfr123.rpg.dungeon.DungeonDefinition.MobSpawn;
import io.github.qpfr123.rpg.dungeon.DungeonDefinition.Point;
import io.github.qpfr123.rpg.loot.LootTable;
import io.github.qpfr123.rpg.loot.LootTable.Entry;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** 던전 정의 모음. 서버에서는 plugins/MinecraftRPG/dungeons/*.yml에서 불러오고, 없으면 기본 던전을 쓴다. */
public final class DungeonRegistry {
    public static final String CRYPT_GENERATOR = "crypt";
    public static final int CRYPT_LAYOUT_VERSION = 2;

    private final Map<String, DungeonDefinition> dungeons = new TreeMap<>();

    /** 기본 던전: 지하 납골당 v2(입구 홀 → 구울 방 → 궁수 2층 방 → 보스 투기장). */
    public static DungeonDefinition defaultCrypt() {
        return new DungeonDefinition("crypt", "지하 납골당", CRYPT_GENERATOR, CRYPT_LAYOUT_VERSION, 4,
                new Point(0.5, 64, -3.5, 0),
                List.of(
                        new MobSpawn("ghoul", -6.5, 64, 28.5), new MobSpawn("ghoul", 6.5, 64, 28.5),
                        new MobSpawn("ghoul", 0.5, 64, 24.5), new MobSpawn("ghoul", 0.5, 64, 33.5),
                        new MobSpawn("bone_archer", -10.5, 67, 54.5), new MobSpawn("bone_archer", 10.5, 67, 58.5),
                        new MobSpawn("bone_archer", -10.5, 67, 62.5),
                        new MobSpawn("ghoul", -3.5, 64, 57.5), new MobSpawn("ghoul", 3.5, 64, 57.5)),
                new MobSpawn("crypt_warden", 0.5, 65, 95.5),
                new LootTable(400, List.of(new Entry("grave_knight_helm", 0.30), new Entry("shield_tonic", 1.0))));
    }

    public static DungeonRegistry withDefaults() {
        DungeonRegistry r = new DungeonRegistry();
        r.put(defaultCrypt());
        return r;
    }

    public void put(DungeonDefinition d) {
        dungeons.put(d.id(), d);
    }

    public Optional<DungeonDefinition> get(String id) {
        return Optional.ofNullable(dungeons.get(id));
    }

    public Collection<DungeonDefinition> all() {
        return List.copyOf(dungeons.values());
    }
}
