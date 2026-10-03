package io.github.qpfr123.rpg.paper;

import io.github.qpfr123.rpg.loot.GearDefinition;
import io.github.qpfr123.rpg.loot.GearRegistry;
import io.github.qpfr123.rpg.loot.LootRoller;
import io.github.qpfr123.rpg.loot.LootTable;
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
import org.bukkit.plugin.Plugin;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.function.Consumer;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * 보상 생성과 수령.
 * <pre>
 * 처치 확정 → 원장에 PENDING 생성(이벤트ID+수령자 유일)
 * 수령: PENDING→CLAIMING 커밋 → 아이템 지급(잠금 표식) + 플레이어 데이터 저장
 *       → EXP 반영 → [CLAIMING→CLAIMED + 프로필 저장] 한 트랜잭션 → 커밋 확인 후 잠금 해제·알림
 * 복구(입장 시 CLAIMING 발견): 잠긴 아이템이 인벤토리·엔더상자에 있으면 지급된 것으로 보고 완료, 없으면 PENDING
 * </pre>
 * 정확히 한 번을 지키는 장치:
 * <ul>
 *   <li>잠긴 아이템은 확정 전까지 인벤토리 밖으로 옮기거나 버리거나 쓰지 못한다({@link ClaimLockListener}).
 *       그래서 "지급됐는데 인벤토리에 없다"는 상황이 생기지 않는다.</li>
 *   <li>아이템은 한 번의 지급·저장으로 함께 들어가므로, 복구 때 일부만 있어도(관리자 삭제 등) 지급된 것으로 본다.
 *       없는 아이템을 다시 주지 않는다.</li>
 *   <li>EXP는 CLAIMED 트랜잭션으로만 영속된다. 완료가 커밋될 때까지 그 플레이어의 일반 저장을 미룬다.
 *       완료 쓰기가 실패하면 같은 내용으로 재시도하며, 성공 알림은 커밋 후에만 보낸다.</li>
 * </ul>
 */
public final class RewardService {
    /** 테스트용 강제 종료 지점. */
    public enum CrashPoint { NONE, AFTER_SAVE, BEFORE_SAVE }

    private static final long RETRY_TICKS_MIN = 5 * 20L;
    private static final long RETRY_TICKS_MAX = 30 * 20L;

    private final Plugin plugin;
    private final DbExecutor db;
    private final ProfileService profiles;
    private final GearRegistry gearRegistry;
    private final GearItems gearItems;
    private final MainThread main;
    private final LootRoller roller;
    private final Logger log;
    private final Set<String> inFlight = new HashSet<>();
    /**
     * 플레이어별 수령 작업 큐. 한 번에 한 보상만 "EXP 반영 → CLAIMED 커밋" 구간에 있도록 직렬화한다.
     * 그렇지 않으면 A의 완료가 실패한 사이 B의 완료 트랜잭션이 A의 EXP까지 저장하고, 이후 A 복구가 EXP를 다시 더한다.
     */
    private final Map<UUID, ArrayDeque<Consumer<Runnable>>> queues = new HashMap<>();
    private volatile CrashPoint crashPoint = CrashPoint.NONE;

    public RewardService(Plugin plugin, DbExecutor db, ProfileService profiles, GearRegistry gearRegistry, GearItems gearItems,
                         MainThread main, LootRoller roller, Logger log) {
        this.plugin = plugin;
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

    static String key(RewardGrant g) {
        return g.eventId() + "|" + g.recipient();
    }

    /** 처치 확정 후 수령자별 보상을 굴려 원장에 기록하고, 접속 중이면 바로 수령을 시도한다. */
    public void onKill(UUID mobEntity, MobStatProfile mob, List<UUID> recipients) {
        for (UUID recipient : recipients) grantLoot("kill:" + mobEntity, recipient, mob.loot(), true);
    }

    /**
     * 보상표를 수령자의 드롭·EXP 보너스로 굴려 (eventId, 수령자) 단위로 한 번만 기록한다.
     * 던전 클리어 보상 등 처치 외 보상도 이 경로를 쓴다.
     */
    public void grantLoot(String eventId, UUID recipient, LootTable table, boolean fromKill) {
        Player player = Bukkit.getPlayer(recipient);
        double dropBonus = 0;
        double expBonus = 0;
        if (player != null && profiles.get(recipient).isPresent()) {
            StatSnapshot s = profiles.stats(player);
            dropBonus = s.dropBonus();
            expBonus = s.expBonus();
        }
        List<String> items = roller.roll(table, dropBonus);
        long exp = ExperienceCurve.applyBonus(table.exp(), expBonus);
        record(new RewardGrant(eventId, recipient, exp, items, RewardGrant.Status.PENDING, System.currentTimeMillis()), true, fromKill);
    }

    /** 관리자 검증용 보상(이벤트 ID "test:..."). 처치 보상과 같은 경로로 기록·수령한다. */
    public void grantTest(Player player, long exp, List<String> gearIds) {
        record(new RewardGrant("test:" + UUID.randomUUID(), player.getUniqueId(), exp, gearIds,
                RewardGrant.Status.PENDING, System.currentTimeMillis()), true, false);
    }

    private void record(RewardGrant grant, boolean claimNow, boolean fromKill) {
        main.then(db.submit("record " + key(grant), d -> d.recordReward(grant, null)), created -> {
            if (!created) {
                log.warning("duplicate reward ignored: " + key(grant));
                return;
            }
            Player online = Bukkit.getPlayer(grant.recipient());
            if (claimNow && online != null) claim(online, grant, fromKill);
        });
    }

    /** 보상함 전체 수령. */
    public void claimAll(Player player) {
        main.then(db.submit("open rewards", d -> d.openRewards(player.getUniqueId())), grants -> {
            List<RewardGrant> pending = grants.stream().filter(g -> g.status() == RewardGrant.Status.PENDING).toList();
            if (pending.isEmpty()) {
                player.sendMessage(Component.text("보상함이 비어 있습니다.", NamedTextColor.GRAY));
                return;
            }
            for (RewardGrant g : pending) claim(player, g, false);
        });
    }

    private void claim(Player player, RewardGrant grant, boolean fromKill) {
        String key = key(grant);
        if (!inFlight.add(key)) return;
        UUID id = player.getUniqueId();
        enqueue(id, done -> { // 대기하는 동안 재접속했을 수 있으므로 실행 시점의 Player를 쓴다
            Player current = Bukkit.getPlayer(id);
            if (current == null) {
                inFlight.remove(key);
                done.run();
            } else {
                claimNow(current, grant, fromKill, key, done);
            }
        });
    }

    /** 작업을 플레이어 큐에 넣는다. 작업은 끝날 때 done을 정확히 한 번 호출해야 다음 작업이 시작된다. */
    private void enqueue(UUID playerId, Consumer<Runnable> job) {
        ArrayDeque<Consumer<Runnable>> q = queues.computeIfAbsent(playerId, k -> new ArrayDeque<>());
        q.add(job);
        if (q.size() == 1) runHead(playerId, q);
    }

    private void runHead(UUID playerId, ArrayDeque<Consumer<Runnable>> q) {
        Consumer<Runnable> job = q.peek();
        if (job == null) {
            queues.remove(playerId, q);
            return;
        }
        boolean[] finished = {false};
        job.accept(() -> {
            if (finished[0]) return;
            finished[0] = true;
            q.poll();
            if (q.isEmpty()) queues.remove(playerId, q);
            else main.nextTick(() -> runHead(playerId, q));
        });
    }

    private void claimNow(Player player, RewardGrant grant, boolean fromKill, String key, Runnable done) {
        Runnable abort = () -> {
            inFlight.remove(key);
            done.run();
        };
        if (!player.isOnline()) {
            abort.run();
            return;
        }
        if (GearItems.freeSlots(player) < grant.gearIds().size()) {
            player.sendMessage(Component.text("인벤토리 공간이 부족해 보상이 보상함에 보관됐습니다. /rpg claim 으로 받으세요.", NamedTextColor.YELLOW));
            abort.run();
            return;
        }
        db.submit("claiming " + key, d -> d.transition(grant.eventId(), grant.recipient(),
                RewardGrant.Status.PENDING, RewardGrant.Status.CLAIMING)).whenComplete((ok, error) -> main.nextTick(() -> {
            if (error != null || !ok) { // 쓰기 실패 또는 이미 다른 경로에서 처리 중: 아무것도 지급하지 않음
                if (error != null && player.isOnline()) {
                    player.sendMessage(Component.text("보상 수령에 실패했습니다. 잠시 후 /rpg claim 으로 다시 시도하세요.", NamedTextColor.RED));
                }
                abort.run();
                return;
            }
            PlayerProfile profile = profiles.get(player.getUniqueId()).orElse(null);
            if (!player.isOnline() || profile == null || GearItems.freeSlots(player) < grant.gearIds().size()) {
                revert(grant, key, done);
                return;
            }
            for (int i = 0; i < grant.gearIds().size(); i++) {
                GearDefinition def = gearRegistry.get(grant.gearIds().get(i)).orElse(null);
                if (def == null) {
                    log.severe("unknown gear in reward " + key + ": " + grant.gearIds().get(i));
                    continue;
                }
                ItemStack item = gearItems.create(def, grant.instanceId(i));
                gearItems.markPending(item, key);
                var leftover = player.getInventory().addItem(item);
                if (!leftover.isEmpty()) log.severe("inventory overflow while delivering " + key); // 사전 검사로 생기지 않아야 함
            }
            haltIf(CrashPoint.BEFORE_SAVE, key);
            player.saveData();
            haltIf(CrashPoint.AFTER_SAVE, key);
            beginCompletion(player.getUniqueId(), profile, grant, fromKill, done);
        }));
    }

    private void haltIf(CrashPoint point, String key) {
        if (crashPoint != point) return;
        log.severe("[crash-test] halting at " + point + " for " + key);
        Runtime.getRuntime().halt(137);
    }

    /** EXP를 메모리에 반영하고, CLAIMED 커밋이 확인될 때까지 재시도한다. */
    /** 플레이어 큐 안에서만 호출된다. 이 보상의 EXP만 반영하고 CLAIMED 커밋까지 재시도한다. */
    private void beginCompletion(UUID playerId, PlayerProfile profile, RewardGrant grant, boolean fromKill, Runnable done) {
        if (profile.completionPending()) { // 직렬화가 깨졌다면 진행하지 않는다(EXP 이중 저장 방지)
            log.severe("completion already pending for " + playerId + ", refusing to start " + key(grant));
            inFlight.remove(key(grant));
            done.run();
            return;
        }
        int levelBefore = profile.level();
        profile.addExp(grant.exp());
        profile.beginCompletion();
        attemptCompletion(playerId, profile, grant, fromKill, levelBefore, RETRY_TICKS_MIN, done);
    }

    private void attemptCompletion(UUID playerId, PlayerProfile profile, RewardGrant grant, boolean fromKill,
                                   int levelBefore, long nextDelay, Runnable done) {
        String key = key(grant);
        PlayerProfile.Snapshot snap = profile.snapshot();
        db.submit("complete " + key, d -> d.completeClaim(grant.eventId(), grant.recipient(), snap))
                .whenComplete((committed, error) -> main.nextTick(() -> {
                    if (error != null) {
                        profile.onSaveFailed(snap);
                        log.warning("claim completion failed for " + key + ", retrying in " + nextDelay / 20 + "s");
                        Bukkit.getScheduler().runTaskLater(plugin, () -> attemptCompletion(playerId, profile, grant, fromKill,
                                levelBefore, Math.min(RETRY_TICKS_MAX, nextDelay * 2), done), nextDelay);
                        return;
                    }
                    profile.endCompletion();
                    inFlight.remove(key);
                    done.run();
                    if (!committed) {
                        // CLAIMING이 아니었다: 다른 경로가 이미 끝냈거나 상태가 어긋났다. 이 스냅샷은 저장되지 않았다.
                        profile.onSaveFailed(snap);
                        log.severe("claim completion skipped, reward not in CLAIMING: " + key + " (EXP " + grant.exp() + " may need admin review)");
                        return;
                    }
                    profile.onSaved(snap);
                    Player player = Bukkit.getPlayer(playerId);
                    if (player == null) return;
                    gearItems.unlock(player, key::equals);
                    notifyClaimed(player, grant, fromKill, levelBefore, profile.level());
                }));
    }

    private void notifyClaimed(Player player, RewardGrant grant, boolean fromKill, int levelBefore, int levelAfter) {
        StringBuilder msg = new StringBuilder("+").append(grant.exp()).append(" EXP");
        for (String id : grant.gearIds()) {
            gearRegistry.get(id).ifPresent(g -> msg.append(", ").append(g.displayName()));
        }
        player.sendMessage(Component.text((fromKill ? "처치 보상: " : "보상 수령: ") + msg, NamedTextColor.GREEN));
        if (levelAfter > levelBefore) {
            player.sendMessage(Component.text("레벨 업! Lv " + levelAfter + " — /rpg stats 로 포인트를 배분하세요.", NamedTextColor.GOLD));
        }
    }

    private void revert(RewardGrant grant, String key, Runnable done) {
        db.submit("revert " + key, d -> d.transition(grant.eventId(), grant.recipient(),
                RewardGrant.Status.CLAIMING, RewardGrant.Status.PENDING))
                .whenComplete((v, e) -> main.nextTick(() -> {
                    inFlight.remove(key);
                    done.run();
                }));
    }

    /**
     * 입장 직후 호출. CLAIMING으로 남은 보상을 인벤토리와 대조해 정리하고, 이미 확정된 보상의 잠금 표식을 푼다.
     */
    public void recover(Player player, List<RewardGrant> open) {
        Set<String> claimingKeys = new HashSet<>();
        int pending = 0;
        for (RewardGrant g : open) {
            if (g.status() == RewardGrant.Status.PENDING) {
                pending++;
                continue;
            }
            String key = key(g);
            claimingKeys.add(key);
            if (!inFlight.add(key)) continue; // 이 서버 실행 중 이미 완료를 재시도하고 있음
            Set<String> ids = new HashSet<>();
            for (int i = 0; i < g.gearIds().size(); i++) ids.add(g.instanceId(i));
            long found = ids.isEmpty() ? 0 : gearItems.countInstances(player, ids);
            if (found > 0) {
                if (found < ids.size()) {
                    log.warning("reward " + key + ": only " + found + "/" + ids.size()
                            + " delivered items found; treating as delivered (no re-delivery), check admin removals");
                } else {
                    log.info("recovered delivered reward " + key + " (" + found + "/" + ids.size() + " items found)");
                }
                UUID pid = player.getUniqueId();
                enqueue(pid, done -> profiles.get(pid).ifPresentOrElse(
                        profile -> beginCompletion(pid, profile, g, false, done),
                        () -> { inFlight.remove(key); done.run(); })); // 처리 전에 나갔으면 다음 입장 때 다시 복구

            } else {
                log.info("reverting undelivered reward " + key + " to PENDING");
                pending++;
                revert(g, key, () -> {});
            }
        }
        // CLAIMED가 커밋된 뒤 잠금 해제 전에 서버가 꺼졌던 아이템
        int unlocked = gearItems.unlock(player, k -> !claimingKeys.contains(k) && !inFlight.contains(k));
        if (unlocked > 0) log.info("unlocked " + unlocked + " already-claimed items for " + player.getName());
        if (pending > 0) {
            player.sendMessage(Component.text("보상함에 받지 않은 보상이 " + pending + "개 있습니다. /rpg claim", NamedTextColor.YELLOW));
        }
    }
}
