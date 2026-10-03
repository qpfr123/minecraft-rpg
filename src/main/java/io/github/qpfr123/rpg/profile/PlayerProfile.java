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
    private long version;
    private boolean dirty;

    public PlayerProfile(UUID id, int level, long exp, StatAllocation allocation, double hp, double mp, double shield, long version) {
        this.id = id;
        this.level = level;
        this.exp = exp;
        this.allocation = allocation;
        this.hp = hp;
        this.mp = mp;
        this.shield = shield;
        this.version = version;
    }

    public static PlayerProfile fresh(UUID id) {
        return new PlayerProfile(id, 1, 0, StatAllocation.empty(), 100, 100, 0, 0);
    }

    public record Snapshot(UUID id, int level, long exp, StatAllocation allocation, double hp, double mp, double shield,
                           long expectedVersion, long newVersion) {}

    /** 저장용 복사본. 버전을 하나 올린다(낙관적 잠금: DB의 version이 expectedVersion일 때만 갱신). */
    public Snapshot snapshot() {
        Snapshot s = new Snapshot(id, level, exp, allocation, hp, mp, shield, version, version + 1);
        version++;
        dirty = false;
        return s;
    }

    public PointAllocationPolicy.Result allocate(SecondaryStat stat, int amount) {
        PointAllocationPolicy.Result r = PointAllocationPolicy.check(allocation, level, stat, amount);
        if (r == PointAllocationPolicy.Result.OK) {
            allocation = allocation.plus(stat, amount);
            dirty = true;
        }
        return r;
    }

    public ExperienceCurve.Progress addExp(long amount) {
        ExperienceCurve.Progress p = ExperienceCurve.add(level, exp, amount);
        level = p.level();
        exp = p.exp();
        dirty = true;
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
    public long version() { return version; }
    public boolean dirty() { return dirty; }

    public void setHp(double v) { if (v != hp) { hp = v; dirty = true; } }
    public void setMp(double v) { if (v != mp) { mp = v; dirty = true; } }
    public void setShield(double v) { if (v != shield) { shield = v; dirty = true; } }

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
        dirty = true;
        return true;
    }
}
