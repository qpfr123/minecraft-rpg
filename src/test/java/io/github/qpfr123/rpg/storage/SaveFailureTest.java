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

/** DB 쓰기 실패 후 재저장·수령 완료 재시도 경로. */
class SaveFailureTest {
    @TempDir Path dir;

    @Test
    void failedSaveKeepsDirtyAndNextSnapshotUsesCommittedVersion() throws Exception {
        UUID id = UUID.randomUUID();
        try (Database db = new Database(dir.resolve("rpg.db"))) {
            PlayerProfile p = PlayerProfile.fresh(id);
            PlayerProfile.Snapshot s1 = p.snapshot();
            db.saveProfile(s1);
            p.onSaved(s1);
            assertFalse(p.dirty());

            p.allocate(SecondaryStat.FOCUS, 2);
            PlayerProfile.Snapshot s2 = p.snapshot();
            db.injectFailures(Database.FaultPoint.SAVE_PROFILE, 1);
            assertThrows(java.sql.SQLException.class, () -> db.saveProfile(s2));
            p.onSaveFailed(s2);
            assertTrue(p.dirty(), "실패한 변경은 다시 저장 대상");
            assertEquals(1, p.version());

            PlayerProfile.Snapshot s3 = p.snapshot();
            assertEquals(1, s3.expectedVersion(), "다음 스냅샷은 DB에 커밋된 버전을 기대");
            db.saveProfile(s3);
            p.onSaved(s3);
            assertFalse(p.dirty());
            assertEquals(2, db.loadProfile(id).orElseThrow().allocation().get(SecondaryStat.FOCUS));
        }
    }

    @Test
    void queuedSnapshotsAfterFailureConvergeWithoutRegression() throws Exception {
        UUID id = UUID.randomUUID();
        try (Database db = new Database(dir.resolve("rpg.db"))) {
            PlayerProfile p = PlayerProfile.fresh(id);
            PlayerProfile.Snapshot s1 = p.snapshot();
            db.saveProfile(s1);
            p.onSaved(s1);

            p.allocate(SecondaryStat.LUCK, 1);
            PlayerProfile.Snapshot a = p.snapshot(); // expected 1
            p.allocate(SecondaryStat.LUCK, 1);
            PlayerProfile.Snapshot b = p.snapshot(); // expected 2 (a 뒤에 대기)
            db.injectFailures(Database.FaultPoint.SAVE_PROFILE, 1);
            assertThrows(java.sql.SQLException.class, () -> db.saveProfile(a));
            assertThrows(Database.StaleVersionException.class, () -> db.saveProfile(b)); // a가 실패했으니 b도 커밋되지 않음
            p.onSaveFailed(a);
            p.onSaveFailed(b);

            PlayerProfile.Snapshot c = p.snapshot();
            db.saveProfile(c);
            p.onSaved(c);
            assertEquals(2, db.loadProfile(id).orElseThrow().allocation().get(SecondaryStat.LUCK));
            assertFalse(p.dirty());
        }
    }

    @Test
    void changesAfterSnapshotStayDirty() {
        PlayerProfile p = PlayerProfile.fresh(UUID.randomUUID());
        PlayerProfile.Snapshot s = p.snapshot();
        p.setHp(50);
        p.onSaved(s);
        assertTrue(p.dirty());
    }

    @Test
    void completeClaimRollsBackOnFailureAndIsIdempotent() throws Exception {
        UUID id = UUID.randomUUID();
        try (Database db = new Database(dir.resolve("rpg.db"))) {
            PlayerProfile p = PlayerProfile.fresh(id);
            PlayerProfile.Snapshot s0 = p.snapshot();
            db.saveProfile(s0);
            p.onSaved(s0);
            RewardGrant g = new RewardGrant("test:1", id, 150, List.of("shield_tonic"), RewardGrant.Status.PENDING, 1);
            db.recordReward(g, null);
            assertTrue(db.transition("test:1", id, RewardGrant.Status.PENDING, RewardGrant.Status.CLAIMING));

            p.addExp(150);
            PlayerProfile.Snapshot s1 = p.snapshot();
            db.injectFailures(Database.FaultPoint.COMPLETE_CLAIM, 1);
            assertThrows(java.sql.SQLException.class, () -> db.completeClaim("test:1", id, s1));
            p.onSaveFailed(s1);
            assertEquals(RewardGrant.Status.CLAIMING, db.rewardStatus("test:1", id).orElseThrow());
            assertEquals(1, db.loadProfile(id).orElseThrow().level(), "실패하면 EXP도 저장되지 않음");

            PlayerProfile.Snapshot s2 = p.snapshot();
            assertTrue(db.completeClaim("test:1", id, s2));
            p.onSaved(s2);
            assertEquals(RewardGrant.Status.CLAIMED, db.rewardStatus("test:1", id).orElseThrow());
            PlayerProfile loaded = db.loadProfile(id).orElseThrow();
            assertEquals(2, loaded.level());
            assertEquals(50, loaded.exp());

            PlayerProfile.Snapshot s3 = p.snapshot();
            assertFalse(db.completeClaim("test:1", id, s3), "두 번째 완료는 아무것도 바꾸지 않음");
            assertEquals(s2.newVersion(), db.loadProfile(id).orElseThrow().version());
        }
    }
}
