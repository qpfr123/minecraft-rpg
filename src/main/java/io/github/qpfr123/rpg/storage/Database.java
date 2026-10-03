package io.github.qpfr123.rpg.storage;

import io.github.qpfr123.rpg.loot.RewardGrant;
import io.github.qpfr123.rpg.profile.PlayerProfile;
import io.github.qpfr123.rpg.stat.SecondaryStat;
import io.github.qpfr123.rpg.stat.StatAllocation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * SQLite 저장소. 스레드 안전하지 않으므로 {@link DbExecutor}의 단일 스레드에서만 호출한다(테스트는 직접 호출).
 */
public final class Database implements AutoCloseable {
    /** 장애 주입 지점(테스트·관리자 검증용). */
    public enum FaultPoint { SAVE_PROFILE, RECORD_REWARD, TRANSITION, COMPLETE_CLAIM }

    private final Connection conn;
    private final Map<FaultPoint, java.util.concurrent.atomic.AtomicInteger> faults = new java.util.concurrent.ConcurrentHashMap<>();

    /** 다음 count번의 해당 쓰기를 실패시킨다. */
    public void injectFailures(FaultPoint point, int count) {
        faults.put(point, new java.util.concurrent.atomic.AtomicInteger(count));
    }

    private void maybeFail(FaultPoint point) throws SQLException {
        var left = faults.get(point);
        if (left != null && left.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            throw new SQLException("injected failure at " + point);
        }
    }

    public Database(Path file) throws SQLException {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
        } catch (java.io.IOException e) {
            throw new SQLException("cannot create db directory", e);
        }
        conn = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=FULL");
            st.execute("PRAGMA foreign_keys=ON");
            st.execute("PRAGMA busy_timeout=5000");
        }
        migrate();
    }

    private void migrate() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS profiles (
                      uuid TEXT PRIMARY KEY,
                      level INTEGER NOT NULL,
                      exp INTEGER NOT NULL,
                      str INTEGER NOT NULL, agi INTEGER NOT NULL, res INTEGER NOT NULL, vit INTEGER NOT NULL,
                      foc INTEGER NOT NULL, luk INTEGER NOT NULL, spi INTEGER NOT NULL,
                      hp REAL NOT NULL, mp REAL NOT NULL, shield REAL NOT NULL,
                      version INTEGER NOT NULL,
                      rule_version INTEGER NOT NULL,
                      updated_at INTEGER NOT NULL)""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS reward_ledger (
                      event_id TEXT NOT NULL,
                      recipient TEXT NOT NULL,
                      exp INTEGER NOT NULL,
                      gear_ids TEXT NOT NULL,
                      status TEXT NOT NULL CHECK (status IN ('PENDING','CLAIMING','CLAIMED')),
                      created_at INTEGER NOT NULL,
                      updated_at INTEGER NOT NULL,
                      PRIMARY KEY (event_id, recipient))""");
            st.execute("CREATE INDEX IF NOT EXISTS reward_ledger_recipient ON reward_ledger(recipient, status)");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS dungeon_sessions (
                      player TEXT PRIMARY KEY,
                      instance_id TEXT NOT NULL,
                      return_world TEXT NOT NULL,
                      x REAL NOT NULL, y REAL NOT NULL, z REAL NOT NULL, yaw REAL NOT NULL, pitch REAL NOT NULL,
                      created_at INTEGER NOT NULL)""");
        }
    }

    // ---- profiles ----

    public Optional<PlayerProfile> loadProfile(UUID id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM profiles WHERE uuid = ?")) {
            ps.setString(1, id.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                Map<SecondaryStat, Integer> m = new EnumMap<>(SecondaryStat.class);
                m.put(SecondaryStat.STRENGTH, rs.getInt("str"));
                m.put(SecondaryStat.AGILITY, rs.getInt("agi"));
                m.put(SecondaryStat.RESISTANCE, rs.getInt("res"));
                m.put(SecondaryStat.VITALITY, rs.getInt("vit"));
                m.put(SecondaryStat.FOCUS, rs.getInt("foc"));
                m.put(SecondaryStat.LUCK, rs.getInt("luk"));
                m.put(SecondaryStat.SPIRIT, rs.getInt("spi"));
                return Optional.of(new PlayerProfile(id, rs.getInt("level"), rs.getLong("exp"), StatAllocation.of(m),
                        rs.getDouble("hp"), rs.getDouble("mp"), rs.getDouble("shield"), rs.getLong("version")));
            }
        }
    }

    /** @throws StaleVersionException DB의 버전이 기대값과 다를 때. */
    public void saveProfile(PlayerProfile.Snapshot s) throws SQLException {
        maybeFail(FaultPoint.SAVE_PROFILE);
        inTransaction(() -> saveProfileNoTx(s));
    }

    private void saveProfileNoTx(PlayerProfile.Snapshot s) throws SQLException {
        long now = System.currentTimeMillis();
        StatAllocation a = s.allocation();
        if (s.expectedVersion() == 0) {
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO profiles(uuid, level, exp, str, agi, res, vit, foc, luk, spi, hp, mp, shield, version, rule_version, updated_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    ON CONFLICT(uuid) DO NOTHING""")) {
                ps.setString(1, s.id().toString());
                ps.setInt(2, s.level());
                ps.setLong(3, s.exp());
                bindAllocation(ps, 4, a);
                ps.setDouble(11, s.hp());
                ps.setDouble(12, s.mp());
                ps.setDouble(13, s.shield());
                ps.setLong(14, s.newVersion());
                ps.setInt(15, PlayerProfile.RULE_VERSION);
                ps.setLong(16, now);
                if (ps.executeUpdate() == 1) return;
            }
            throw new StaleVersionException(s.id(), s.expectedVersion());
        }
        try (PreparedStatement ps = conn.prepareStatement("""
                UPDATE profiles SET level=?, exp=?, str=?, agi=?, res=?, vit=?, foc=?, luk=?, spi=?, hp=?, mp=?, shield=?,
                  version=?, rule_version=?, updated_at=?
                WHERE uuid=? AND version=?""")) {
            ps.setInt(1, s.level());
            ps.setLong(2, s.exp());
            bindAllocation(ps, 3, a);
            ps.setDouble(10, s.hp());
            ps.setDouble(11, s.mp());
            ps.setDouble(12, s.shield());
            ps.setLong(13, s.newVersion());
            ps.setInt(14, PlayerProfile.RULE_VERSION);
            ps.setLong(15, now);
            ps.setString(16, s.id().toString());
            ps.setLong(17, s.expectedVersion());
            if (ps.executeUpdate() != 1) throw new StaleVersionException(s.id(), s.expectedVersion());
        }
    }

    private static void bindAllocation(PreparedStatement ps, int start, StatAllocation a) throws SQLException {
        SecondaryStat[] order = {SecondaryStat.STRENGTH, SecondaryStat.AGILITY, SecondaryStat.RESISTANCE,
                SecondaryStat.VITALITY, SecondaryStat.FOCUS, SecondaryStat.LUCK, SecondaryStat.SPIRIT};
        for (int i = 0; i < order.length; i++) ps.setInt(start + i, a.get(order[i]));
    }

    // ---- reward ledger ----

    /**
     * 보상 생성과 수령자 프로필 저장을 한 트랜잭션으로 묶는다. 보상 행이 이미 있으면 전체를 되돌리고 false.
     * EXP는 프로필 스냅샷에 이미 반영돼 있어야 한다.
     */
    public boolean recordReward(RewardGrant grant, PlayerProfile.Snapshot recipientProfile) throws SQLException {
        maybeFail(FaultPoint.RECORD_REWARD);
        try {
            inTransaction(() -> {
                insertReward(grant);
                if (recipientProfile != null) saveProfileNoTx(recipientProfile);
            });
            return true;
        } catch (SQLException e) {
            if (!isUniqueViolation(e)) throw e;
            // 중복 보상: 원장은 그대로 두고, 버전 연속성을 위해 프로필만 저장한다.
            if (recipientProfile != null) saveProfile(recipientProfile);
            return false;
        }
    }

    private void insertReward(RewardGrant g) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO reward_ledger(event_id, recipient, exp, gear_ids, status, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?)""")) {
            ps.setString(1, g.eventId());
            ps.setString(2, g.recipient().toString());
            ps.setLong(3, g.exp());
            ps.setString(4, String.join(",", g.gearIds()));
            ps.setString(5, g.status().name());
            ps.setLong(6, g.createdAt());
            ps.setLong(7, g.createdAt());
            ps.executeUpdate();
        }
    }

    /** 상태 전이. from 상태일 때만 바뀐다. @return 바뀌었는지 여부. */
    public boolean transition(String eventId, UUID recipient, RewardGrant.Status from, RewardGrant.Status to) throws SQLException {
        maybeFail(FaultPoint.TRANSITION);
        return transitionNoFault(eventId, recipient, from, to);
    }

    private boolean transitionNoFault(String eventId, UUID recipient, RewardGrant.Status from, RewardGrant.Status to) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE reward_ledger SET status=?, updated_at=? WHERE event_id=? AND recipient=? AND status=?")) {
            ps.setString(1, to.name());
            ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, eventId);
            ps.setString(4, recipient.toString());
            ps.setString(5, from.name());
            return ps.executeUpdate() == 1;
        }
    }

    /**
     * 수령 완료: CLAIMING → CLAIMED 전이와 EXP가 반영된 프로필 저장을 한 트랜잭션으로 묶는다.
     * @return 전이했으면 true. 보상이 CLAIMING이 아니면 아무것도 바꾸지 않고 false.
     * @throws SQLException 쓰기 실패(전부 롤백) — 호출자는 같은 내용으로 다시 시도한다.
     */
    public boolean completeClaim(String eventId, UUID recipient, PlayerProfile.Snapshot profile) throws SQLException {
        maybeFail(FaultPoint.COMPLETE_CLAIM);
        boolean[] ok = {false};
        inTransaction(() -> {
            if (!transitionNoFault(eventId, recipient, RewardGrant.Status.CLAIMING, RewardGrant.Status.CLAIMED)) return;
            saveProfileNoTx(profile);
            ok[0] = true;
        });
        return ok[0];
    }

    public Optional<RewardGrant.Status> rewardStatus(String eventId, UUID recipient) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT status FROM reward_ledger WHERE event_id=? AND recipient=?")) {
            ps.setString(1, eventId);
            ps.setString(2, recipient.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(RewardGrant.Status.valueOf(rs.getString(1))) : Optional.empty();
            }
        }
    }

    /** 수령하지 않은 보상(PENDING, CLAIMING). */
    public List<RewardGrant> openRewards(UUID recipient) throws SQLException {
        return queryRewards("SELECT * FROM reward_ledger WHERE recipient=? AND status<>'CLAIMED' ORDER BY created_at", recipient, -1);
    }

    public List<RewardGrant> recentRewards(UUID recipient, int limit) throws SQLException {
        return queryRewards("SELECT * FROM reward_ledger WHERE recipient=? ORDER BY created_at DESC LIMIT ?", recipient, limit);
    }

    private List<RewardGrant> queryRewards(String sql, UUID recipient, int limit) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, recipient.toString());
            if (limit > 0) ps.setInt(2, limit);
            List<RewardGrant> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String gear = rs.getString("gear_ids");
                    out.add(new RewardGrant(rs.getString("event_id"), UUID.fromString(rs.getString("recipient")),
                            rs.getLong("exp"), gear.isEmpty() ? List.of() : Arrays.asList(gear.split(",")),
                            RewardGrant.Status.valueOf(rs.getString("status")), rs.getLong("created_at")));
                }
            }
            return out;
        }
    }

    // ---- dungeon sessions ----

    /** 던전 입장 기록: 서버가 꺼져도 다음 입장 때 원래 위치로 돌려보낼 수 있게 한다. */
    public record DungeonSession(UUID player, String instanceId, String returnWorld, double x, double y, double z,
                                 float yaw, float pitch) {}

    public void saveDungeonSessions(List<DungeonSession> sessions) throws SQLException {
        inTransaction(() -> {
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO dungeon_sessions(player, instance_id, return_world, x, y, z, yaw, pitch, created_at)
                    VALUES (?,?,?,?,?,?,?,?,?)
                    ON CONFLICT(player) DO UPDATE SET instance_id=excluded.instance_id, return_world=excluded.return_world,
                      x=excluded.x, y=excluded.y, z=excluded.z, yaw=excluded.yaw, pitch=excluded.pitch, created_at=excluded.created_at""")) {
                long now = System.currentTimeMillis();
                for (DungeonSession s : sessions) {
                    ps.setString(1, s.player().toString());
                    ps.setString(2, s.instanceId());
                    ps.setString(3, s.returnWorld());
                    ps.setDouble(4, s.x());
                    ps.setDouble(5, s.y());
                    ps.setDouble(6, s.z());
                    ps.setFloat(7, s.yaw());
                    ps.setFloat(8, s.pitch());
                    ps.setLong(9, now);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        });
    }

    public Optional<DungeonSession> loadDungeonSession(UUID player) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM dungeon_sessions WHERE player=?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new DungeonSession(player, rs.getString("instance_id"), rs.getString("return_world"),
                        rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"), rs.getFloat("yaw"), rs.getFloat("pitch")));
            }
        }
    }

    /** instanceId가 일치할 때만 지운다(그 사이 다른 던전에 들어갔다면 남긴다). null이면 무조건 지운다. */
    public void deleteDungeonSession(UUID player, String instanceId) throws SQLException {
        String sql = instanceId == null ? "DELETE FROM dungeon_sessions WHERE player=?"
                : "DELETE FROM dungeon_sessions WHERE player=? AND instance_id=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, player.toString());
            if (instanceId != null) ps.setString(2, instanceId);
            ps.executeUpdate();
        }
    }

    // ---- backup ----

    /** 온라인 백업. 대상 파일이 이미 있으면 실패한다. */
    public void backupTo(Path target) throws SQLException {
        try {
            Files.createDirectories(target.toAbsolutePath().getParent());
        } catch (java.io.IOException e) {
            throw new SQLException("cannot create backup directory", e);
        }
        try (PreparedStatement ps = conn.prepareStatement("VACUUM INTO ?")) {
            ps.setString(1, target.toAbsolutePath().toString());
            ps.execute();
        }
    }

    // ---- helpers ----

    @FunctionalInterface
    private interface SqlWork { void run() throws SQLException; }

    private void inTransaction(SqlWork work) throws SQLException {
        boolean auto = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            work.run();
            conn.commit();
        } catch (SQLException | RuntimeException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(auto);
        }
    }

    private static boolean isUniqueViolation(SQLException e) {
        String msg = String.valueOf(e.getMessage());
        return msg.contains("UNIQUE constraint failed") || msg.contains("PRIMARY KEY");
    }

    @Override
    public void close() throws SQLException {
        conn.close();
    }

    public static final class StaleVersionException extends SQLException {
        public StaleVersionException(UUID id, long expected) {
            super("stale profile version for " + id + " (expected " + expected + ")");
        }
    }
}
