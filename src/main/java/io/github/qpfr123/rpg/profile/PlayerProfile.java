package io.github.qpfr123.rpg.profile;

import io.github.qpfr123.rpg.stat.PointAllocationPolicy;
import io.github.qpfr123.rpg.stat.SecondaryStat;
import io.github.qpfr123.rpg.stat.StatAllocation;

import java.util.UUID;

/**
 * 플레이어 성장의 권위 상태. 메인 스레드에서만 변경하고, 저장 시 {@link #snapshot()}으로 복사본을 DB 스레드에 넘긴다.
 */
public final class PlayerProfile {
    public static final int RULE_VERSION = 1;

    private final UUID id;
    private int level;
    private long exp;
    private StatAllocation allocation;
    private double hp;
    private double mp;
    private double shield;
    /** DB 커밋이 확인된 버전. */
    private long persistedVersion;
    /** 마지막으로 DB 스레드에 넘긴 스냅샷의 버전. 실패하면 persistedVersion으로 되돌린다. */
    private long submittedVersion;
    private long modCount;
    private long savedModCount;
    /** 보상 수령 완료가 아직 커밋되지 않은 건수. 0보다 크면 일반 저장을 미룬다. */
    private int pendingCompletions;

    public PlayerProfile(UUID id, int level, long exp, StatAllocation allocation, double hp, double mp, double shield, long version) {
        this.id = id;
        this.level = level;
        this.exp = exp;
        this.allocation = allocation;
        this.hp = hp;
        this.mp = mp;
        this.shield = shield;
        this.persistedVersion = version;
        this.submittedVersion = version;
    }

    public static PlayerProfile fresh(UUID id) {
        return new PlayerProfile(id, 1, 0, StatAllocation.empty(), 100, 100, 0, 0);
    }

    public record Snapshot(UUID id, int level, long exp, StatAllocation allocation, double hp, double mp, double shield,
                           long expectedVersion, long newVersion, long modCount) {
        public Snapshot(UUID id, int level, long exp, StatAllocation allocation, double hp, double mp, double shield,
                        long expectedVersion, long newVersion) {
            this(id, level, exp, allocation, hp, mp, shield, expectedVersion, newVersion, 0);
        }
    }

    /**
     * 저장용 복사본(낙관적 잠금: DB의 version이 expectedVersion일 때만 갱신). 결과는 반드시
     * {@link #onSaved} 또는 {@link #onSaveFailed}로 알려야 한다. 스냅샷은 만든 순서대로 DB 스레드에서 실행되므로
     * 오래된 스냅샷이 새 스냅샷 뒤에 커밋되는 일은 없다.
     */
    public Snapshot snapshot() {
        Snapshot s = new Snapshot(id, level, exp, allocation, hp, mp, shield, submittedVersion, submittedVersion + 1, modCount);
        submittedVersion++;
        return s;
    }

    public void onSaved(Snapshot s) {
        persistedVersion = Math.max(persistedVersion, s.newVersion());
        submittedVersion = Math.max(submittedVersion, persistedVersion);
        savedModCount = Math.max(savedModCount, s.modCount());
    }

    /** 쓰기 실패: 다음 스냅샷이 DB의 실제 버전을 기대하도록 되돌린다. 변경분은 dirty로 남아 다시 저장된다. */
    public void onSaveFailed(Snapshot s) {
        submittedVersion = persistedVersion;
    }

    public void beginCompletion() { pendingCompletions++; }
    public void endCompletion() { pendingCompletions = Math.max(0, pendingCompletions - 1); }
    public boolean completionPending() { return pendingCompletions > 0; }

    public PointAllocationPolicy.Result allocate(SecondaryStat stat, int amount) {
        PointAllocationPolicy.Result r = PointAllocationPolicy.check(allocation, level, stat, amount);
        if (r == PointAllocationPolicy.Result.OK) {
            allocation = allocation.plus(stat, amount);
            modCount++;
        }
        return r;
    }

    public ExperienceCurve.Progress addExp(long amount) {
        ExperienceCurve.Progress p = ExperienceCurve.add(level, exp, amount);
        level = p.level();
        exp = p.exp();
        modCount++;
        return p;
    }

    public UUID id() { return id; }
    public int level() { return level; }
    public long exp() { return exp; }
    public StatAllocation allocation() { return allocation; }
    public int unspentPoints() { return PointAllocationPolicy.unspent(allocation, level); }
    public double hp() { return hp; }
    public double mp() { return mp; }
    public double shield() { return shield; }
    /** DB에 커밋이 확인된 버전. */
    public long version() { return persistedVersion; }
    /** 메모리 상태가 반영된(또는 반영 중인) 최신 버전. */
    public long latestVersion() { return submittedVersion; }
    public boolean dirty() { return modCount != savedModCount; }

    public void setHp(double v) { if (v != hp) { hp = v; modCount++; } }
    public void setMp(double v) { if (v != mp) { mp = v; modCount++; } }
    public void setShield(double v) { if (v != shield) { shield = v; modCount++; } }

    /** 최대치가 줄었을 때 현재 자원을 자른다. 늘었을 때 무료 회복은 하지 않는다. */
    public void clampTo(int maxHp, int maxMp, int shieldCap) {
        setHp(Math.min(hp, maxHp));
        setMp(Math.min(mp, maxMp));
        setShield(Math.min(shield, shieldCap));
    }

    /** 관리자 테스트용: 레벨을 올리기만 한다(내리면 배분량이 지급량을 넘을 수 있음). */
    public boolean raiseLevelForAdmin(int newLevel) {
        if (newLevel <= level || newLevel > io.github.qpfr123.rpg.config.Balance.MAX_LEVEL) return false;
        level = newLevel;
        exp = 0;
        modCount++;
        return true;
    }
}
