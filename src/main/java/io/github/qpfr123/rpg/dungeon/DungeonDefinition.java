package io.github.qpfr123.rpg.dungeon;

import io.github.qpfr123.rpg.loot.LootTable;

import java.util.List;

/**
 * 던전 정의. 좌표는 템플릿 월드 기준이다. layoutVersion이 바뀌면 템플릿을 다시 만든다.
 */
public record DungeonDefinition(
        String id,
        String displayName,
        int layoutVersion,
        int maxPlayers,
        Point entrance,
        List<MobSpawn> spawns,
        MobSpawn boss,
        LootTable clearReward) {

    public record Point(double x, double y, double z, float yaw) {}

    public record MobSpawn(String mobId, double x, double y, double z) {}

    public DungeonDefinition {
        spawns = List.copyOf(spawns);
    }

    public String templateWorldName() {
        return "rpg_tpl_" + id;
    }
}
