package io.github.qpfr123.rpg.mob;

import io.github.qpfr123.rpg.config.Balance;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 몹 하나에 대한 피해 기여·소유권·처치 확정 상태. 메인 스레드 전용.
 * <ul>
 *   <li>일반 몹: 첫 타격자가 소유한다. 소유자가 {@link Balance#MOB_CLAIM_EXPIRE_MILLIS} 동안 때리지 않으면 다음 타격자에게 넘어간다.</li>
 *   <li>보스: 최대 HP의 {@link Balance#BOSS_CONTRIBUTION_RATIO} 이상 피해를 준 플레이어 전원이 각자 받는다.</li>
 * </ul>
 */
public final class MobEngagement {
    private final boolean boss;
    private final int maxHp;
    private final Map<UUID, Double> contribution = new HashMap<>();
    private UUID owner;
    private long ownerLastHit;
    private boolean killed;

    public MobEngagement(boolean boss, int maxHp) {
        this.boss = boss;
        this.maxHp = maxHp;
    }

    public void recordHit(UUID player, double damage, long nowMillis) {
        if (killed) return;
        contribution.merge(player, Math.max(0, damage), Double::sum);
        if (owner == null || (!owner.equals(player) && nowMillis - ownerLastHit >= Balance.MOB_CLAIM_EXPIRE_MILLIS)) {
            owner = player;
        }
        if (owner.equals(player)) ownerLastHit = nowMillis;
    }

    /** 처치를 한 번만 확정한다. 두 번째 호출부터는 빈 목록. */
    public List<UUID> confirmKill() {
        if (killed) return List.of();
        killed = true;
        if (!boss) return owner == null ? List.of() : List.of(owner);
        double threshold = maxHp * Balance.BOSS_CONTRIBUTION_RATIO;
        List<UUID> out = new ArrayList<>();
        contribution.forEach((p, dmg) -> {
            if (dmg >= threshold) out.add(p);
        });
        return out;
    }

    public boolean killed() {
        return killed;
    }

    public UUID owner() {
        return owner;
    }

    public double contributionOf(UUID player) {
        return contribution.getOrDefault(player, 0.0);
    }
}
