package io.github.qpfr123.rpg.stat;

import io.github.qpfr123.rpg.config.Balance;

/**
 * 포인트 지급량과 단일 스탯 50% 투자 상한. 상한의 분모는 "지급한 총 포인트"다(미사용 포인트 포함).
 */
public final class PointAllocationPolicy {
    private PointAllocationPolicy() {}

    public enum Result { OK, INVALID_AMOUNT, NOT_ENOUGH_POINTS, CAP_EXCEEDED }

    public static int grantedPoints(int level) {
        if (level < 1) throw new IllegalArgumentException("level < 1");
        return Balance.INITIAL_POINTS + Balance.POINTS_PER_LEVEL * (level - 1);
    }

    public static int singleStatCap(int level) {
        return (int) Math.floor(grantedPoints(level) * Balance.SINGLE_STAT_CAP_RATIO);
    }

    public static int unspent(StatAllocation allocation, int level) {
        return grantedPoints(level) - allocation.total();
    }

    public static Result check(StatAllocation allocation, int level, SecondaryStat stat, int amount) {
        if (amount <= 0) return Result.INVALID_AMOUNT;
        if (amount > unspent(allocation, level)) return Result.NOT_ENOUGH_POINTS;
        if (allocation.get(stat) + amount > singleStatCap(level)) return Result.CAP_EXCEEDED;
        return Result.OK;
    }
}
