package io.github.qpfr123.rpg.paper;

import io.github.qpfr123.rpg.combat.AttackerStats;
import io.github.qpfr123.rpg.combat.CombatStateTracker;
import io.github.qpfr123.rpg.combat.CooldownPolicy;
import io.github.qpfr123.rpg.combat.DamagePipeline;
import io.github.qpfr123.rpg.combat.DamageRequest;
import io.github.qpfr123.rpg.combat.DamageResult;
import io.github.qpfr123.rpg.combat.DefenderState;
import io.github.qpfr123.rpg.config.Balance;
import io.github.qpfr123.rpg.mob.MobEngagement;
import io.github.qpfr123.rpg.mob.MobStatProfile;
import io.github.qpfr123.rpg.profile.PlayerProfile;
import io.github.qpfr123.rpg.stat.StatSnapshot;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.projectiles.ProjectileSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 바닐라 피해 이벤트 → RPG {@link DamagePipeline} 어댑터.
 * 이미 취소된 이벤트는 무시하고, 처리한 이벤트는 바닐라 피해를 0으로 만든다(피격 연출·넉백은 유지).
 * 바닐라 갑옷·인챈트·무기 피해는 쓰지 않으므로 RPG DEF와 이중 적용되지 않는다.
 */
public final class CombatListener implements Listener {
    private final ProfileService profiles;
    private final MobService mobs;
    private final RewardService rewards;
    private final HealthDisplay display;
    private final CombatStateTracker combat;
    private final DamagePipeline pipeline;
    private final MainThread main;
    private final Map<UUID, Long> lastBasicAttack = new HashMap<>();

    public CombatListener(ProfileService profiles, MobService mobs, RewardService rewards, HealthDisplay display,
                          CombatStateTracker combat, DamagePipeline pipeline, MainThread main) {
        this.profiles = profiles;
        this.mobs = mobs;
        this.rewards = rewards;
        this.display = display;
        this.combat = combat;
        this.pipeline = pipeline;
        this.main = main;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        Entity victim = event.getEntity();
        Entity source = event instanceof EntityDamageByEntityEvent byEntity ? resolveSource(byEntity.getDamager()) : null;
        if (victim instanceof Player player) {
            onPlayerDamaged(event, player, source);
        } else if (victim instanceof LivingEntity living) {
            mobs.profileOf(living).ifPresent(profile -> onMobDamaged(event, living, profile, source));
        }
    }

    // ---------- 플레이어가 맞음 ----------

    private void onPlayerDamaged(EntityDamageEvent event, Player player, Entity source) {
        Optional<PlayerProfile> maybe = profiles.get(player.getUniqueId());
        if (maybe.isEmpty()) { // 로드 전: 조작 잠금
            event.setCancelled(true);
            return;
        }
        PlayerProfile profile = maybe.get();
        if (source instanceof Player) { // PvP 비활성
            event.setCancelled(true);
            return;
        }
        DamageRequest request = playerRequest(event, source);
        StatSnapshot stats = profiles.stats(player);
        DamageResult r = pipeline.apply(request, new DefenderState(profile.hp(), profile.shield(), stats.maxHp(), stats.def()));
        profile.setHp(r.newHp());
        profile.setShield(r.newShield());
        if (request instanceof DamageRequest.Attack) combat.markHostile(player.getUniqueId(), System.currentTimeMillis());
        event.setDamage(0);
        display.sync(player, profile, stats.maxHp());
    }

    private DamageRequest playerRequest(EntityDamageEvent event, Entity source) {
        DamageCause cause = event.getCause();
        if (cause == DamageCause.VOID || cause == DamageCause.KILL) return new DamageRequest.TrueKill();
        if (source instanceof LivingEntity attacker) {
            Optional<MobStatProfile> mob = mobs.profileOf(attacker);
            if (mob.isPresent()) return new DamageRequest.Attack(AttackerStats.flat(mob.get().atk()), 1.0);
            // 태그 없는 바닐라 몹(크리퍼 포함): 원피해 × 시험 배율
            return new DamageRequest.Attack(AttackerStats.flat(event.getDamage() * Balance.VANILLA_MOB_DAMAGE_SCALE), 1.0);
        }
        return new DamageRequest.Environment(event.getDamage());
    }

    // ---------- RPG 몹이 맞음 ----------

    private void onMobDamaged(EntityDamageEvent event, LivingEntity mob, MobStatProfile profile, Entity source) {
        DamageCause cause = event.getCause();
        if (cause == DamageCause.VOID || cause == DamageCause.KILL) return; // 바닐라 제거에 맡김(보상 없음)
        MobEngagement engagement = mobs.engagement(mob, profile);
        if (engagement.killed()) {
            event.setCancelled(true);
            return;
        }
        DamageRequest request;
        Player attacker = null;
        if (source instanceof Player p) {
            if (profiles.get(p.getUniqueId()).isEmpty()) {
                event.setCancelled(true);
                return;
            }
            StatSnapshot stats = profiles.stats(p);
            long now = System.currentTimeMillis();
            long last = lastBasicAttack.getOrDefault(p.getUniqueId(), 0L);
            if (now - last < CooldownPolicy.basicAttackIntervalMillis(stats.attackSpeed())) {
                event.setCancelled(true); // 기본 공격 간격 미달
                return;
            }
            lastBasicAttack.put(p.getUniqueId(), now);
            request = new DamageRequest.Attack(AttackerStats.of(stats), 1.0);
            attacker = p;
        } else if (source != null) {
            event.setCancelled(true); // 몹끼리 싸움 등은 RPG 몹 HP에 영향 없음
            return;
        } else {
            request = new DamageRequest.Environment(event.getDamage());
        }

        double hp = mobs.hp(mob, profile);
        DamageResult r = pipeline.apply(request, new DefenderState(hp, 0, profile.maxHp(), profile.def()));
        mobs.setHp(mob, profile, r.newHp());
        event.setDamage(0);

        if (attacker != null) {
            long now = System.currentTimeMillis();
            engagement.recordHit(attacker.getUniqueId(), r.hpDamage(), now);
            combat.markHostile(attacker.getUniqueId(), now);
            if (r.attackerHeal() > 0) heal(attacker, r.attackerHeal());
            Player hitter = attacker;
            main.nextTick(() -> mob.setNoDamageTicks(0)); // 공격 속도가 바닐라 무적 시간에 막히지 않게
            if (r.critical()) hitter.sendActionBar(net.kyori.adventure.text.Component.text("치명타! " + Math.round(r.hpDamage()),
                    net.kyori.adventure.text.format.NamedTextColor.GOLD));
        }

        if (r.lethal()) {
            List<UUID> recipients = engagement.confirmKill();
            UUID id = mob.getUniqueId();
            main.nextTick(() -> {
                if (mob.isValid()) mob.setHealth(0);
            });
            rewards.onKill(id, profile, recipients);
        }
    }

    private void heal(Player player, double amount) {
        profiles.get(player.getUniqueId()).ifPresent(profile -> {
            StatSnapshot stats = profiles.stats(player);
            if (profile.hp() <= 0) return;
            profile.setHp(Math.min(stats.maxHp(), profile.hp() + amount));
            display.sync(player, profile, stats.maxHp());
        });
    }

    private static Entity resolveSource(Entity damager) {
        if (damager instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            return shooter instanceof Entity e ? e : null;
        }
        return damager;
    }

    /** 바닐라 자동 회복(포만감·물약·비콘 등)은 RPG HP를 우회하므로 막는다. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onRegain(EntityRegainHealthEvent event) {
        if (event.getEntity() instanceof Player || mobs.profileOf(event.getEntity()).isPresent()) {
            event.setCancelled(true);
        }
    }

    public void forget(UUID player) {
        lastBasicAttack.remove(player);
    }
}
