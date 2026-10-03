package io.github.qpfr123.rpg.stat;

import java.util.EnumMap;
import java.util.Map;

/** 2차 스탯별 영구 투자량. 불변 객체. */
public final class StatAllocation {
    private final EnumMap<SecondaryStat, Integer> points;

    private StatAllocation(EnumMap<SecondaryStat, Integer> points) {
        this.points = points;
    }

    public static StatAllocation empty() {
        return new StatAllocation(new EnumMap<>(SecondaryStat.class));
    }

    public static StatAllocation of(Map<SecondaryStat, Integer> source) {
        EnumMap<SecondaryStat, Integer> copy = new EnumMap<>(SecondaryStat.class);
        source.forEach((stat, value) -> {
            if (value < 0) throw new IllegalArgumentException("negative allocation for " + stat);
            if (value > 0) copy.put(stat, value);
        });
        return new StatAllocation(copy);
    }

    public int get(SecondaryStat stat) {
        return points.getOrDefault(stat, 0);
    }

    public int total() {
        int sum = 0;
        for (int v : points.values()) sum += v;
        return sum;
    }

    public StatAllocation plus(SecondaryStat stat, int amount) {
        EnumMap<SecondaryStat, Integer> copy = new EnumMap<>(points);
        copy.merge(stat, amount, Integer::sum);
        return new StatAllocation(copy);
    }

    public Map<SecondaryStat, Integer> asMap() {
        return Map.copyOf(points);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof StatAllocation other && other.points.equals(points);
    }

    @Override
    public int hashCode() {
        return points.hashCode();
    }

    @Override
    public String toString() {
        return points.toString();
    }
}
