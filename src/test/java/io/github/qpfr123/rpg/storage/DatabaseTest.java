package io.github.qpfr123.rpg.storage;

import io.github.qpfr123.rpg.loot.RewardGrant;
import io.github.qpfr123.rpg.profile.PlayerProfile;
import io.github.qpfr123.rpg.stat.SecondaryStat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 실제 SQLite 파일을 쓰는 통합 테스트. */
class DatabaseTest {
    @TempDir Path dir;

    @Test
    void profileRoundTripAndOptimisticVersion() throws Exception {
        UUID id = UUID.randomUUID();
        try (Database db = new Database(dir.resolve("rpg.db"))) {
            assertTrue(db.loadProfile(id).isEmpty());
            PlayerProfile p = PlayerProfile.fresh(id);
            p.allocate(SecondaryStat.VITALITY, 5);
            p.addExp(150);
            p.setHp(42.5);
            db.saveProfile(p.snapshot());
            PlayerProfile.Snapshot second = p.snapshot();
            db.saveProfile(second);
            assertThrows(Database.StaleVersionException.class, () -> db.saveProfile(second)); // 같은 버전 재사용 거부
        }
        try (Database db = new Database(dir.resolve("rpg.db"))) { // 재접속 = 재오픈
            PlayerProfile loaded = db.loadProfile(id).orElseThrow();
            assertEquals(2, loaded.level());
            assertEquals(50, loaded.exp());
            assertEquals(5, loaded.allocation().get(SecondaryStat.VITALITY));
            assertEquals(42.5, loaded.hp());
            assertEquals(2, loaded.version());
        }
    }

    @Test
    void rewardIsCreatedOncePerEventAndRecipient() throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        try (Database db = new Database(dir.resolve("rpg.db"))) {
            RewardGrant g = new RewardGrant("mob:1", a, 30, List.of("ghoul_blade"), RewardGrant.Status.PENDING, 1);
            assertTrue(db.recordReward(g, null));
            assertFalse(db.recordReward(g, null));
            assertTrue(db.recordReward(new RewardGrant("mob:1", b, 30, List.of(), RewardGrant.Status.PENDING, 1), null));
            assertEquals(1, db.openRewards(a).size());
            assertEquals(1, db.openRewards(b).size());
        }
    }

    @Test
    void rewardAndProfileCommitTogether() throws Exception {
        UUID a = UUID.randomUUID();
        try (Database db = new Database(dir.resolve("rpg.db"))) {
            PlayerProfile p = PlayerProfile.fresh(a);
            db.saveProfile(p.snapshot());
            PlayerProfile.Snapshot stale = new PlayerProfile.Snapshot(a, 3, 0, p.allocation(), 100, 100, 0, 99, 100);
            RewardGrant g = new RewardGrant("mob:2", a, 30, List.of(), RewardGrant.Status.PENDING, 1);
            assertThrows(Database.StaleVersionException.class, () -> db.recordReward(g, stale));
            assertTrue(db.openRewards(a).isEmpty(), "프로필 저장 실패 시 보상도 롤백");
            assertEquals(1, db.loadProfile(a).orElseThrow().level());
        }
    }

    @Test
    void claimTransitionsOnlyFromExpectedState() throws Exception {
        UUID a = UUID.randomUUID();
        try (Database db = new Database(dir.resolve("rpg.db"))) {
            db.recordReward(new RewardGrant("mob:3", a, 0, List.of("bone_bow"), RewardGrant.Status.PENDING, 1), null);
            assertTrue(db.transition("mob:3", a, RewardGrant.Status.PENDING, RewardGrant.Status.CLAIMING));
            assertFalse(db.transition("mob:3", a, RewardGrant.Status.PENDING, RewardGrant.Status.CLAIMING));
            assertEquals(RewardGrant.Status.CLAIMING, db.openRewards(a).getFirst().status());
            assertTrue(db.transition("mob:3", a, RewardGrant.Status.CLAIMING, RewardGrant.Status.CLAIMED));
            assertTrue(db.openRewards(a).isEmpty());
            assertEquals(1, db.recentRewards(a, 10).size());
        }
    }

    @Test
    void backupRestoresSameData() throws Exception {
        UUID id = UUID.randomUUID();
        Path backup = dir.resolve("backups/rpg-1.db");
        try (Database db = new Database(dir.resolve("rpg.db"))) {
            PlayerProfile p = PlayerProfile.fresh(id);
            p.addExp(500);
            p.allocate(SecondaryStat.LUCK, 3);
            db.saveProfile(p.snapshot());
            db.recordReward(new RewardGrant("mob:4", id, 0, List.of(), RewardGrant.Status.PENDING, 1), null);
            db.backupTo(backup);
        }
        try (Database restored = new Database(backup)) {
            PlayerProfile r = restored.loadProfile(id).orElseThrow();
            assertEquals(3, r.allocation().get(SecondaryStat.LUCK));
            assertEquals(ExperienceCurveLevel.of(500), r.level());
            assertEquals(1, restored.openRewards(id).size());
        }
    }

    private static final class ExperienceCurveLevel {
        static int of(long exp) {
            return io.github.qpfr123.rpg.profile.ExperienceCurve.add(1, 0, exp).level();
        }
    }
}
