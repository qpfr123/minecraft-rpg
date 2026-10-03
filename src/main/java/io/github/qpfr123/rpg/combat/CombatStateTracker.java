package io.github.qpfr123.rpg.combat;

import io.github.qpfr123.rpg.config.Balance;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 적대 행동을 하거나 받으면 전투 상태. 마지막 적대 행동 후 일정 시간이 지나면 비전투. */
public final class CombatStateTracker {
    private final Map<UUID, Long> lastHostile = new ConcurrentHashMap<>();

    public void markHostile(UUID id, long nowMillis) {
        lastHostile.put(id, nowMillis);
    }

    public boolean inCombat(UUID id, long nowMillis) {
        Long last = lastHostile.get(id);
        return last != null && nowMillis - last < Balance.COMBAT_TIMEOUT_MILLIS;
    }

    public void forget(UUID id) {
        lastHostile.remove(id);
    }
}
