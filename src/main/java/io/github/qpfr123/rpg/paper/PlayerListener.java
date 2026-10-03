package io.github.qpfr123.rpg.paper;

import io.github.qpfr123.rpg.combat.CombatStateTracker;
import io.github.qpfr123.rpg.loot.GearDefinition;
import io.github.qpfr123.rpg.loot.GearSlot;
import io.github.qpfr123.rpg.config.Balance;
import io.github.qpfr123.rpg.profile.PlayerProfile;
import io.github.qpfr123.rpg.stat.StatSnapshot;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.logging.Level;

public final class PlayerListener implements Listener {
    private final ProfileService profiles;
    private final RewardService rewards;
    private final MobService mobs;
    private final GearItems gear;
    private final HealthDisplay display;
    private final CombatStateTracker combat;
    private final CombatListener combatListener;
    private final MainThread main;
    private final SidebarService sidebar;

    public PlayerListener(ProfileService profiles, RewardService rewards, MobService mobs, GearItems gear,
                          HealthDisplay display, CombatStateTracker combat, CombatListener combatListener, MainThread main,
                          SidebarService sidebar) {
        this.sidebar = sidebar;
        this.profiles = profiles;
        this.rewards = rewards;
        this.mobs = mobs;
        this.gear = gear;
        this.display = display;
        this.combat = combat;
        this.combatListener = combatListener;
        this.main = main;
    }

    /** 로드가 끝나야 입장할 수 있다(로드 전 조작 잠금). 비동기 스레드라 DB 결과를 기다려도 된다. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
        try {
            profiles.preload(event.getUniqueId());
        } catch (Exception e) {
            profiles.log().log(Level.SEVERE, "profile load failed for " + event.getName(), e);
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    Component.text("RPG 데이터를 불러오지 못했습니다. 잠시 후 다시 접속하세요."));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        ProfileService.Loaded loaded = profiles.activate(player.getUniqueId());
        if (loaded == null) {
            player.kick(Component.text("RPG 데이터가 준비되지 않았습니다. 다시 접속하세요."));
            return;
        }
        PlayerProfile profile = loaded.profile();
        StatSnapshot stats = profiles.stats(player);
        if (profile.latestVersion() == 0) { // DB에 한 번도 저장된 적 없는 새 프로필
            profile.setHp(stats.maxHp());
            profile.setMp(stats.maxMp());
            player.sendMessage(Component.text("Minecraft RPG에 오신 것을 환영합니다. /rpg stats 로 포인트 10점을 배분하세요.", NamedTextColor.GOLD));
            profiles.save(profile);
        }
        profile.clampTo(stats.maxHp(), stats.maxMp(), stats.shieldCap());
        display.sync(player, profile, stats.maxHp());
        rewards.recover(player, loaded.openRewards());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        var id = event.getPlayer().getUniqueId();
        profiles.unload(id);
        combat.forget(id);
        combatListener.forget(id);
        display.forget(id);
        sidebar.forget(id);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        profiles.get(event.getPlayer().getUniqueId()).ifPresent(p -> {
            p.setHp(0);
            p.setShield(0);
        });
    }

    /** 사망 페널티(2026-10-03 결정: 보존): RPG 장비·소모품은 떨어지지 않고 그대로 남는다. 바닐라 아이템은 바닐라 규칙. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onDeathKeepGear(PlayerDeathEvent event) {
        if (event.getKeepInventory()) return;
        var it = event.getDrops().iterator();
        while (it.hasNext()) {
            ItemStack item = it.next();
            if (gear.definitionOf(item).isPresent()) {
                event.getItemsToKeep().add(item);
                it.remove();
            }
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        display.respawned(player);
        combat.forget(player.getUniqueId());
        main.nextTick(() -> profiles.get(player.getUniqueId()).ifPresent(p -> {
            StatSnapshot stats = profiles.stats(player);
            p.setHp(stats.maxHp());
            p.setMp(stats.maxMp());
            p.setShield(0);
            display.sync(player, p, stats.maxHp());
        }));
    }

    /** 방어막 강장제: 최대 HP 10% 방어막, 총량은 최대 HP 50%까지. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = event.getItem();
        GearDefinition def = gear.definitionOf(item).orElse(null);
        if (def == null || def.slot() != GearSlot.CONSUMABLE) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (gear.isLocked(item)) {
            player.sendActionBar(Component.text("보상 확정 중인 아이템은 아직 쓸 수 없습니다.", NamedTextColor.YELLOW));
            return;
        }
        profiles.get(player.getUniqueId()).ifPresent(p -> {
            if (p.hp() <= 0) return;
            StatSnapshot stats = profiles.stats(player);
            double before = p.shield();
            double after = Math.min(stats.shieldCap(), before + stats.maxHp() * Balance.SHIELD_TONIC_RATIO);
            if (after <= before) {
                player.sendMessage(Component.text("방어막이 이미 상한(최대 HP 50%)입니다.", NamedTextColor.GRAY));
                return;
            }
            p.setShield(after);
            item.setAmount(item.getAmount() - 1);
            player.sendMessage(Component.text("방어막 +" + Math.round(after - before), NamedTextColor.AQUA));
        });
    }

    /** RPG 아이템은 바닐라 음식/음료로 소비되지 않는다. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (gear.definitionOf(event.getItem()).isPresent()) event.setCancelled(true);
    }

    /** 자연 스폰 좀비·스켈레톤을 RPG 몹으로 변환. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        CreatureSpawnEvent.SpawnReason reason = event.getSpawnReason();
        if (reason != CreatureSpawnEvent.SpawnReason.NATURAL && reason != CreatureSpawnEvent.SpawnReason.SPAWNER) return;
        LivingEntity entity = event.getEntity();
        if (mobs.profileOf(entity).isPresent()) return;
        mobs.registry().naturalConversion(entity.getType().name()).ifPresent(p -> mobs.tag(entity, p));
    }

    /** RPG 몹의 바닐라 드롭·경험치 구슬 제거. 보상은 원장으로만 나간다. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onMobDeath(EntityDeathEvent event) {
        if (event instanceof PlayerDeathEvent) return;
        if (mobs.profileOf(event.getEntity()).isEmpty()) return;
        event.getDrops().clear();
        event.setDroppedExp(0);
        mobs.forget(event.getEntity().getUniqueId());
    }
}
