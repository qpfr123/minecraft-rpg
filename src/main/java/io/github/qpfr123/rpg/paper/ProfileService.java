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

    public ProfileService(DbExecutor db, GearItems gear, Logger log) {
        this.db = db;
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
        if (r != null && r.profile().version() > l.profile().version()) {
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

    public CompletableFuture<Void> save(PlayerProfile profile) {
        PlayerProfile.Snapshot s = profile.snapshot();
        return db.submit("save " + s.id(), d -> {
            d.saveProfile(s);
            return null;
        });
    }

    public void saveDirty() {
        for (PlayerProfile p : online.values()) {
            if (p.dirty()) save(p);
        }
    }

    public void unload(UUID id) {
        PlayerProfile p = online.remove(id);
        if (p != null) {
            save(p);
            recent.put(id, new Recent(p, System.currentTimeMillis()));
        }
    }

    public void purgeRecent(long olderThanMillis) {
        long cutoff = System.currentTimeMillis() - olderThanMillis;
        recent.values().removeIf(r -> r.at() < cutoff);
        // 사전 로드 후 입장하지 못한 항목도 정리
    }

    public void saveAll() {
        for (PlayerProfile p : online.values()) save(p);
    }

    public Logger log() {
        return log;
    }
}
