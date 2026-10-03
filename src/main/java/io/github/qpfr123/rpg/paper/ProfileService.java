package io.github.qpfr123.rpg.paper;

import io.github.qpfr123.rpg.loot.RewardGrant;
import io.github.qpfr123.rpg.profile.PlayerProfile;
import io.github.qpfr123.rpg.stat.StatCalculator;
import io.github.qpfr123.rpg.stat.StatSnapshot;
import io.github.qpfr123.rpg.storage.DbExecutor;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * 접속 중 플레이어 프로필 캐시. 로드는 비동기 사전 로그인 단계에서 끝내므로, 입장한 플레이어는 항상 프로필이 있다
 * (로드 전 조작 잠금). 저장은 DB 스레드에 순서대로 맡긴다.
 */
public final class ProfileService {
    public record Loaded(PlayerProfile profile, List<RewardGrant> openRewards) {}

    private final DbExecutor db;
    private final GearItems gear;
    private final Logger log;
    private final Map<UUID, Loaded> preloaded = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerProfile> online = new ConcurrentHashMap<>();
    /** 방금 나간 플레이어의 프로필. 재접속 시 사전 로드가 퇴장 저장보다 먼저 DB를 읽은 경우를 보정한다. */
    private final Map<UUID, Recent> recent = new ConcurrentHashMap<>();

    private record Recent(PlayerProfile profile, long at) {}

    private final MainThread main;

    public ProfileService(DbExecutor db, GearItems gear, MainThread main, Logger log) {
        this.db = db;
        this.main = main;
        this.gear = gear;
        this.log = log;
    }

    /** 비동기 사전 로그인 스레드에서 호출. 블로킹 허용. */
    public void preload(UUID id) throws Exception {
        Loaded loaded = db.submit("load " + id, d -> new Loaded(
                d.loadProfile(id).orElseGet(() -> PlayerProfile.fresh(id)), d.openRewards(id))).get();
        preloaded.put(id, loaded);
    }

    /** 입장 시 호출. 사전 로드가 없으면 null(입장을 거부해야 함). */
    public Loaded activate(UUID id) {
        Loaded l = preloaded.remove(id);
        if (l == null) return null;
        Recent r = recent.remove(id);
        if (r != null && r.profile().latestVersion() >= l.profile().version()) {
            l = new Loaded(r.profile(), l.openRewards()); // 메모리의 최신 상태가 권위
        }
        online.put(id, l.profile());
        return l;
    }

    public Optional<PlayerProfile> get(UUID id) {
        return Optional.ofNullable(online.get(id));
    }

    public PlayerProfile require(Player p) {
        PlayerProfile profile = online.get(p.getUniqueId());
        if (profile == null) throw new IllegalStateException("profile not loaded: " + p.getName());
        return profile;
    }

    public Collection<PlayerProfile> onlineProfiles() {
        return online.values();
    }

    public StatSnapshot stats(Player p) {
        return StatCalculator.compute(require(p).allocation(), gear.equippedBonuses(p));
    }

    /**
     * 비동기 저장. 결과는 메인 스레드에서 프로필 버전 상태에 반영한다. 보상 수령 완료가 커밋되기 전에는
     * 일반 저장을 미룬다(완료 트랜잭션이 프로필을 함께 저장하므로, 그 전에 EXP만 따로 저장되면 복구 시 이중 반영된다).
     */
    public CompletableFuture<Void> save(PlayerProfile profile) {
        if (profile.completionPending()) return CompletableFuture.completedFuture(null);
        PlayerProfile.Snapshot s = profile.snapshot();
        CompletableFuture<Void> f = db.submit("save " + s.id(), d -> {
            d.saveProfile(s);
            return null;
        });
        f.whenComplete((v, e) -> main.nextTick(() -> {
            if (e == null) profile.onSaved(s);
            else profile.onSaveFailed(s);
        }));
        return f;
    }

    /** 접속 중이거나 방금 나간 프로필 중 저장되지 않은 변경이 있는 것을 저장(실패한 저장의 재시도 포함). */
    public void saveDirty() {
        for (PlayerProfile p : online.values()) {
            if (p.dirty()) save(p);
        }
        for (Recent r : recent.values()) {
            if (r.profile().dirty()) save(r.profile());
        }
    }

    public void unload(UUID id) {
        PlayerProfile p = online.remove(id);
        if (p != null) {
            save(p);
            recent.put(id, new Recent(p, System.currentTimeMillis()));
        }
    }

    /** 오래됐고 저장이 끝난 퇴장 프로필만 버린다. */
    public void purgeRecent(long olderThanMillis) {
        long cutoff = System.currentTimeMillis() - olderThanMillis;
        recent.values().removeIf(r -> r.at() < cutoff && !r.profile().dirty() && !r.profile().completionPending());
    }

    /** 종료 시: 미뤄 둔 것을 제외한 전부 저장. 수령 완료가 커밋되지 않은 프로필은 다음 입장 때 복구 절차가 처리한다. */
    public void saveAll() {
        for (PlayerProfile p : online.values()) save(p);
        for (Recent r : recent.values()) {
            if (r.profile().dirty()) save(r.profile());
        }
    }

    public Logger log() {
        return log;
    }
}
