package io.github.qpfr123.rpg.dungeon;

import io.github.qpfr123.rpg.mob.MobRegistry;
import io.github.qpfr123.rpg.mob.MobStatProfile;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** 던전 정의 검사. 저장·입장 전에 쓴다. 빈 목록이면 통과. */
public final class DungeonValidator {
    public static final Pattern ID = Pattern.compile("[a-z0-9_]{2,24}");

    private DungeonValidator() {}

    public static List<String> validate(DungeonDefinition d, MobRegistry mobs) {
        List<String> errors = new ArrayList<>();
        if (!ID.matcher(d.id()).matches()) errors.add("ID는 영문 소문자·숫자·밑줄 2~24자여야 합니다: " + d.id());
        if (d.displayName() == null || d.displayName().isBlank()) errors.add("표시 이름이 없습니다");
        if (d.maxPlayers() < 1 || d.maxPlayers() > 4) errors.add("최대 인원은 1~4명이어야 합니다");
        if (d.entrance() == null) errors.add("입구가 없습니다(setentrance)");
        if (d.boss() == null) {
            errors.add("보스가 없습니다(setboss <몹ID>)");
        } else {
            MobStatProfile b = mobs.get(d.boss().mobId()).orElse(null);
            if (b == null) errors.add("알 수 없는 보스 몹: " + d.boss().mobId());
            else if (!b.boss()) errors.add("보스 자리에는 보스 몹만 둘 수 있습니다: " + b.id());
        }
        for (DungeonDefinition.MobSpawn s : d.spawns()) {
            MobStatProfile m = mobs.get(s.mobId()).orElse(null);
            if (m == null) errors.add("알 수 없는 몹: " + s.mobId());
            else if (m.boss()) errors.add("일반 스폰에 보스 몹을 둘 수 없습니다: " + s.mobId() + " (setboss 사용)");
        }
        if (d.spawns().size() > 60) errors.add("일반 스폰은 60개까지입니다");
        if (d.clearReward() == null) errors.add("클리어 보상이 없습니다");
        return errors;
    }
}
