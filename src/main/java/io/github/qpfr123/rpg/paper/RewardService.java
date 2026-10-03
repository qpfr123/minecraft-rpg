package io.github.qpfr123.rpg.paper;

import io.github.qpfr123.rpg.loot.GearDefinition;
import io.github.qpfr123.rpg.loot.GearRegistry;
import io.github.qpfr123.rpg.loot.LootRoller;
import io.github.qpfr123.rpg.loot.RewardGrant;
import io.github.qpfr123.rpg.mob.MobStatProfile;
import io.github.qpfr123.rpg.profile.ExperienceCurve;
import io.github.qpfr123.rpg.profile.PlayerProfile;
import io.github.qpfr123.rpg.stat.StatSnapshot;
import io.github.qpfr123.rpg.storage.DbExecutor;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 보상 생성과 수령.
 * <pre>
 * 처치 확정 → 원장에 PENDING 생성(이벤트ID+수령자 유일)
 * 수령: PENDING→CLAIMING(DB 커밋) → 아이템 지급 + 플레이어 데이터 저장 → EXP 반영 → [CLAIMING→CLAIMED + 프로필 저장] 한 트랜잭션
 * 복구(입장 시 CLAIMING 발견): 인벤토리·엔더상자에서 인스턴스 ID를 찾으면 지급된 것으로 보고 완료 처리, 없으면 PENDING으로 되돌림
 * </pre>
 * EXP는 CLAIMED 트랜잭션에서만 영속되므로 아이템과 EXP 모두 정확히 한 번 반영된다.
 */
public final class RewardService {
    private final DbExecutor db;
    private final ProfileService profiles;
    private final GearRegistry gearRegistry;
    private final GearItems gearItems;
    private final MainThread main;
    private final LootRoller roller;
    private final Logger log;
    private final Set<String> inFlight = new HashSet<>();
    /** 테스트용 강제 종료 지점. */
    public enum CrashPoint { NONE, AFTER_SAVE, BEFORE_SAVE }

    private volatile CrashPoint crashPoint = CrashPoint.NONE;

    public RewardService(DbExecutor db, ProfileService profiles, GearRegistry gearRegistry, GearItems gearItems,
                         MainThread main, LootRoller roller, Logger log) {
        this.db = db;
        this.profiles = profiles;
        this.gearRegistry = gearRegistry;
        this.gearItems = gearItems;
        this.main = main;
        this.roller = roller;
        this.log = log;
    }

    /**
     * 다음 수령에서 서버를 강제 종료한다. AFTER_SAVE: 아이템 지급·플레이어 데이터 저장 후, CLAIMED 커밋 전.
     * BEFORE_SAVE: 아이템 지급 후 플레이어 데이터 저장 전(재시작하면 아이템이 사라진 상태).
     */
    public void armCrashTest(CrashPoint point) {
        crashPoint = point;
    }

    /** 처치 확정 후 수령자별 보상을 굴려 원장에 기록하고, 접속 중이면 바로 수령을 시도한다. */
    public void onKill(UUID mobEntity, MobStatProfile mob, List<UUID> recipients) {
        String eventId = "kill:" + mobEntity;
        long now = System.currentTimeMillis();
        for (UUID recipient : recipients) {
            Player player = Bukkit.getPlayer(recipient);
            double dropBonus = 0;
            double expBonus = 0;
            if (player != null && profiles.get(recipient).isPresent()) {
                StatSnapshot s = profiles.stats(player);
                dropBonus = s.dropBonus();
                expBonus = s.expBonus();
            }
            List<String> items = roller.roll(mob.loot(), dropBonus);
            long exp = ExperienceCurve.applyBonus(mob.loot().exp(), expBonus);
            RewardGrant grant = new RewardGrant(eventId, recipient, exp, items, RewardGrant.Status.PENDING, now);
            main.then(db.submit("record " + eventId, d -> d.recordReward(grant, null)), created -> {
                if (!created) {
                    log.warning("duplicate reward ignored: " + eventId + " / " + recipient);
                    return;
                }
                Player online = Bukkit.getPlayer(recipient);
                if (online != null) claim(online, grant, true);
            });
        }
    }

    /** 보상함 전체 수령. */
    public void claimAll(Player player) {
        main.then(db.submit("open rewards", d -> d.openRewards(player.getUniqueId())), grants -> {
            if (grants.isEmpty()) {
                player.sendMessage(Component.text("보상함이 비어 있습니다.", NamedTextColor.GRAY));
                return;
            }
            for (RewardGrant g : grants) {
                if (g.status() == RewardGrant.Status.PENDING) claim(player, g, false);
            }
        });
    }

    private void claim(Player player, RewardGrant grant, boolean fromKill) {
        String key = grant.eventId() + "|" + grant.recipient();
        if (!inFlight.add(key)) return;
        if (GearItems.freeSlots(player) < grant.gearIds().size()) {
            inFlight.remove(key);
            player.sendMessage(Component.text("인벤토리 공간이 부족해 보상이 보상함에 보관됐습니다. /rpg claim 으로 받으세요.", NamedTextColor.YELLOW));
            return;
        }
        main.then(db.submit("claiming " + key, d -> d.transition(grant.eventId(), grant.recipient(),
                RewardGrant.Status.PENDING, RewardGrant.Status.CLAIMING)), ok -> {
            if (!ok) {
                inFlight.remove(key);
                return;
            }
            if (!player.isOnline() || profiles.get(player.getUniqueId()).isEmpty()
                    || GearItems.freeSlots(player) < grant.gearIds().size()) {
                revert(grant, key);
                return;
            }
            for (int i = 0; i < grant.gearIds().size(); i++) {
                GearDefinition def = gearRegistry.get(grant.gearIds().get(i)).orElse(null);
                if (def == null) {
                    log.severe("unknown gear in reward " + key + ": " + grant.gearIds().get(i));
                    continue;
                }
                ItemStack item = gearItems.create(def, grant.instanceId(i));
                player.getInventory().addItem(item);
            }
            haltIf(CrashPoint.BEFORE_SAVE, key);
            player.saveData();
            haltIf(CrashPoint.AFTER_SAVE, key);
            complete(player, grant, key, fromKill);
        });
    }

    private void haltIf(CrashPoint point, String key) {
        if (crashPoint != point) return;
        log.severe("[crash-test] halting at " + point + " for " + key);
        Runtime.getRuntime().halt(137);
    }

    private void complete(Player player, RewardGrant grant, String key, boolean fromKill) {
        PlayerProfile profile = profiles.require(player);
        int before = profile.level();
        profile.addExp(grant.exp());
        PlayerProfile.Snapshot snap = profile.snapshot();
        db.submit("complete " + key, d -> d.completeClaim(grant.eventId(), grant.recipient(), snap))
                .whenComplete((v, e) -> main.nextTick(() -> inFlight.remove(key)));
        StringBuilder msg = new StringBuilder("+").append(grant.exp()).append(" EXP");
        for (String id : grant.gearIds()) {
            gearRegistry.get(id).ifPresent(g -> msg.append(", ").append(g.displayName()));
        }
        player.sendMessage(Component.text((fromKill ? "처치 보상: " : "보상 수령: ") + msg, NamedTextColor.GREEN));
        if (profile.level() > before) {
            player.sendMessage(Component.text("레벨 업! Lv " + profile.level() + " — /rpg stats 로 포인트를 배분하세요.", NamedTextColor.GOLD));
        }
    }

    private void revert(RewardGrant grant, String key) {
        db.submit("revert " + key, d -> d.transition(grant.eventId(), grant.recipient(),
                RewardGrant.Status.CLAIMING, RewardGrant.Status.PENDING))
                .whenComplete((v, e) -> main.nextTick(() -> inFlight.remove(key)));
    }

    /** 입장 직후 호출: 지급 도중 종료로 CLAIMING에 남은 보상을 대조해 정리하고, 남은 PENDING을 알린다. */
    public void recover(Player player, List<RewardGrant> open) {
        int pending = 0;
        for (RewardGrant g : open) {
            if (g.status() == RewardGrant.Status.PENDING) {
                pending++;
                continue;
            }
            String key = g.eventId() + "|" + g.recipient();
            Set<String> ids = new HashSet<>();
            for (int i = 0; i < g.gearIds().size(); i++) ids.add(g.instanceId(i));
            long found = ids.isEmpty() ? 0 : gearItems.countInstances(player, ids);
            inFlight.add(key);
            if (found > 0) {
                log.info("recovered delivered reward " + key + " (" + found + "/" + ids.size() + " items found)");
                complete(player, g, key, false);
            } else {
                log.info("reverting undelivered reward " + key + " to PENDING");
                pending++;
                revert(g, key);
            }
        }
        if (pending > 0) {
            player.sendMessage(Component.text("보상함에 받지 않은 보상이 " + pending + "개 있습니다. /rpg claim", NamedTextColor.YELLOW));
        }
    }

    public void logFailure(String what, Throwable t) {
        log.log(Level.SEVERE, what, t);
    }
}
