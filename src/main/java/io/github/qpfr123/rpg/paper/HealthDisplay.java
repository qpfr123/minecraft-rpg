package io.github.qpfr123.rpg.paper;

import io.github.qpfr123.rpg.profile.PlayerProfile;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * RPG HP → 바닐라 체력 표시. 살아 있으면 max(0.5, 20×HP/최대HP), RPG HP가 0일 때만 바닐라 사망.
 */
public final class HealthDisplay {
    private final Set<UUID> dying = new HashSet<>();
    private final MainThread main;

    public HealthDisplay(MainThread main) {
        this.main = main;
    }

    public void sync(Player player, PlayerProfile profile, int maxHp) {
        if (player.isDead()) return;
        AttributeInstance max = player.getAttribute(Attribute.MAX_HEALTH);
        double vanillaMax = max == null ? 20 : max.getValue();
        if (profile.hp() <= 0) {
            kill(player);
            return;
        }
        double target = Math.max(0.5, vanillaMax * profile.hp() / Math.max(1, maxHp));
        target = Math.min(vanillaMax, target);
        if (Math.abs(player.getHealth() - target) > 1e-6) player.setHealth(target);
    }

    /**
     * 재진입 방지: 사망 처리 중 다시 죽이지 않는다. 피해 이벤트 안에서 바로 죽이면 같은 피격이 사망을 두 번 일으킬 수
     * 있어 다음 틱에 처리한다.
     */
    public void kill(Player player) {
        if (!dying.add(player.getUniqueId())) return;
        main.nextTick(() -> {
            if (player.isOnline() && !player.isDead()) player.setHealth(0);
        });
    }

    public void respawned(Player player) {
        dying.remove(player.getUniqueId());
    }

    public void forget(UUID id) {
        dying.remove(id);
    }
}
