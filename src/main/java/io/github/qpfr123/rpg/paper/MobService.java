package io.github.qpfr123.rpg.paper;

import io.github.qpfr123.rpg.combat.DamagePipeline;
import io.github.qpfr123.rpg.mob.MobEngagement;
import io.github.qpfr123.rpg.mob.MobRegistry;
import io.github.qpfr123.rpg.mob.MobStatProfile;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * RPG 몹 상태. 현재 HP는 엔티티 PDC에 매 피격마다 기록하므로 청크 언로드/로드 후에도 남는다.
 * 기여·소유권은 메모리에 두며, 서버 재시작 시 초기화된다(슬라이스 1 한계).
 */
public final class MobService {
    private static final double BOSS_BAR_RANGE = 48;

    private final Keys keys;
    private final MobRegistry registry;
    private final Map<UUID, MobEngagement> engagements = new HashMap<>();
    private final Map<UUID, BossBar> bossBars = new HashMap<>();
    private final Map<UUID, Set<UUID>> bossViewers = new HashMap<>();

    public MobService(Keys keys, MobRegistry registry) {
        this.keys = keys;
        this.registry = registry;
    }

    public MobRegistry registry() {
        return registry;
    }

    public Optional<MobStatProfile> profileOf(Entity entity) {
        String id = entity.getPersistentDataContainer().get(keys.mobProfile, PersistentDataType.STRING);
        return id == null ? Optional.empty() : registry.get(id);
    }

    public double hp(LivingEntity entity, MobStatProfile profile) {
        Double hp = entity.getPersistentDataContainer().get(keys.mobHp, PersistentDataType.DOUBLE);
        return hp == null ? profile.maxHp() : hp;
    }

    public void setHp(LivingEntity entity, MobStatProfile profile, double hp) {
        entity.getPersistentDataContainer().set(keys.mobHp, PersistentDataType.DOUBLE, Math.max(0, hp));
        updateName(entity, profile, hp);
        BossBar bar = bossBars.get(entity.getUniqueId());
        if (bar != null) bar.progress((float) Math.max(0, Math.min(1, hp / profile.maxHp())));
    }

    /** 바닐라 몹에 RPG 프로필을 입힌다. */
    public void tag(LivingEntity entity, MobStatProfile profile) {
        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        pdc.set(keys.mobProfile, PersistentDataType.STRING, profile.id());
        pdc.set(keys.mobHp, PersistentDataType.DOUBLE, (double) profile.maxHp());
        entity.setCustomNameVisible(true);
        updateName(entity, profile, profile.maxHp());
        if (profile.boss()) {
            entity.setRemoveWhenFarAway(false);
            entity.setPersistent(true);
            ensureBossBar(entity, profile);
        }
    }

    public LivingEntity spawn(MobStatProfile profile, Location at) {
        EntityType type = EntityType.valueOf(profile.entityType());
        Entity e = at.getWorld().spawnEntity(at, type);
        if (!(e instanceof LivingEntity living)) {
            e.remove();
            throw new IllegalStateException(type + " is not living");
        }
        tag(living, profile);
        return living;
    }

    public MobEngagement engagement(LivingEntity entity, MobStatProfile profile) {
        return engagements.computeIfAbsent(entity.getUniqueId(), k -> new MobEngagement(profile.boss(), profile.maxHp()));
    }

    public Optional<MobEngagement> existingEngagement(UUID entity) {
        return Optional.ofNullable(engagements.get(entity));
    }

    public void forget(UUID entity) {
        engagements.remove(entity);
        BossBar bar = bossBars.remove(entity);
        Set<UUID> viewers = bossViewers.remove(entity);
        if (bar != null && viewers != null) {
            for (UUID v : viewers) {
                Player p = Bukkit.getPlayer(v);
                if (p != null) p.hideBossBar(bar);
            }
        }
    }

    private void updateName(LivingEntity entity, MobStatProfile profile, double hp) {
        NamedTextColor color = profile.boss() ? NamedTextColor.DARK_RED : NamedTextColor.RED;
        entity.customName(Component.text(profile.displayName() + " ", color)
                .append(Component.text(DamagePipeline.displayHp(hp) + "/" + profile.maxHp(), NamedTextColor.WHITE)));
    }

    private BossBar ensureBossBar(LivingEntity entity, MobStatProfile profile) {
        return bossBars.computeIfAbsent(entity.getUniqueId(), k -> BossBar.bossBar(
                Component.text(profile.displayName(), NamedTextColor.DARK_RED),
                (float) Math.max(0, Math.min(1, hp(entity, profile) / profile.maxHp())),
                BossBar.Color.RED, BossBar.Overlay.NOTCHED_10));
    }

    /** 1초마다: 근처 플레이어에게 보스바 표시/숨김, 사라진 보스 정리. */
    public void tickBossBars() {
        for (var world : Bukkit.getWorlds()) {
            for (LivingEntity e : world.getLivingEntities()) {
                profileOf(e).filter(MobStatProfile::boss).ifPresent(p -> ensureBossBar(e, p));
            }
        }
        Iterator<Map.Entry<UUID, BossBar>> it = bossBars.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            Entity boss = Bukkit.getEntity(entry.getKey());
            Set<UUID> viewers = bossViewers.computeIfAbsent(entry.getKey(), k -> new HashSet<>());
            for (Player p : Bukkit.getOnlinePlayers()) {
                boolean near = boss != null && boss.isValid() && !boss.isDead() && p.getWorld().equals(boss.getWorld())
                        && p.getLocation().distanceSquared(boss.getLocation()) <= BOSS_BAR_RANGE * BOSS_BAR_RANGE;
                if (near && viewers.add(p.getUniqueId())) p.showBossBar(entry.getValue());
                if (!near && viewers.remove(p.getUniqueId())) p.hideBossBar(entry.getValue());
            }
            if (boss == null || !boss.isValid() || boss.isDead()) {
                for (UUID v : viewers) {
                    Player p = Bukkit.getPlayer(v);
                    if (p != null) p.hideBossBar(entry.getValue());
                }
                bossViewers.remove(entry.getKey());
                it.remove();
            }
        }
    }
}
