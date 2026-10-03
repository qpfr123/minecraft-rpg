package io.github.qpfr123.rpg.dungeon;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 던전 인스턴스의 상태·멤버·동시 수 상한·유휴 정리 판단. Paper에 의존하지 않는 순수 로직(메인 스레드 전용).
 * <ul>
 *   <li>멤버는 입장 시 확정된다. 파티가 바뀌어도 진행 중인 인스턴스의 멤버는 바뀌지 않는다.</li>
 *   <li>준비 중(CREATING)인 인스턴스도 상한에 포함한다.</li>
 *   <li>안에 접속 중인 멤버가 없는 상태가 idle 시간 이상 이어지면 닫는다. 클리어 후에는 귀환 지연이 끝나면 닫는다.</li>
 * </ul>
 */
public final class InstanceTracker {
    public enum State { CREATING, ACTIVE, CLEARED, CLOSING }

    public enum EnterResult { OK, CAP_REACHED, ALREADY_INSIDE, TOO_MANY_PLAYERS }

    public static final class Instance {
        private final String id;
        private final String dungeonId;
        private final Set<UUID> members;
        private State state = State.CREATING;
        private long emptySince = -1;
        private long clearedAt = -1;

        Instance(String id, String dungeonId, Collection<UUID> members) {
            this.id = id;
            this.dungeonId = dungeonId;
            this.members = new LinkedHashSet<>(members);
        }

        public String id() { return id; }
        public String dungeonId() { return dungeonId; }
        public Set<UUID> members() { return Set.copyOf(members); }
        public State state() { return state; }
        public boolean isMember(UUID p) { return members.contains(p); }
        public long clearedAt() { return clearedAt; }
    }

    private final Map<String, Instance> instances = new HashMap<>();
    private int cap;
    private long idleMillis;
    private long exitDelayMillis;

    public InstanceTracker(int cap, long idleMillis, long exitDelayMillis) {
        this.cap = cap;
        this.idleMillis = idleMillis;
        this.exitDelayMillis = exitDelayMillis;
    }

    public void configure(int cap, long idleMillis, long exitDelayMillis) {
        this.cap = cap;
        this.idleMillis = idleMillis;
        this.exitDelayMillis = exitDelayMillis;
    }

    public int cap() { return cap; }
    public long idleMillis() { return idleMillis; }
    public long exitDelayMillis() { return exitDelayMillis; }

    public EnterResult canCreate(Collection<UUID> players, int maxPlayers) {
        if (players.size() > maxPlayers) return EnterResult.TOO_MANY_PLAYERS;
        for (UUID p : players) if (instanceOf(p).isPresent()) return EnterResult.ALREADY_INSIDE;
        if (openCount() >= cap) return EnterResult.CAP_REACHED;
        return EnterResult.OK;
    }

    /** 검사를 통과했을 때만 생성한다. */
    public Optional<Instance> create(String id, String dungeonId, Collection<UUID> players, int maxPlayers) {
        if (canCreate(players, maxPlayers) != EnterResult.OK) return Optional.empty();
        Instance i = new Instance(id, dungeonId, players);
        instances.put(id, i);
        return Optional.of(i);
    }

    public void activate(String id, long now) {
        Instance i = instances.get(id);
        if (i != null && i.state == State.CREATING) {
            i.state = State.ACTIVE;
            i.emptySince = now; // 입장 텔레포트가 끝나면 onPresence가 갱신
        }
    }

    /** 보스 처치 확정은 한 번만. */
    public boolean markCleared(String id, long now) {
        Instance i = instances.get(id);
        if (i == null || i.state != State.ACTIVE) return false;
        i.state = State.CLEARED;
        i.clearedAt = now;
        return true;
    }

    /** 멤버가 스스로 나감: 이후 다시 들어올 수 없다. */
    public void removeMember(String id, UUID player) {
        Instance i = instances.get(id);
        if (i != null) i.members.remove(player);
    }

    /** 주기 점검: 인스턴스 안에 접속 중인 멤버 수를 알려 준다. */
    public void onPresence(String id, int onlineInside, long now) {
        Instance i = instances.get(id);
        if (i == null) return;
        if (onlineInside > 0) i.emptySince = -1;
        else if (i.emptySince < 0) i.emptySince = now;
    }

    /** 닫아야 할 인스턴스 목록(상태를 CLOSING으로 바꾼다). */
    public List<Instance> dueForClose(long now) {
        List<Instance> out = new ArrayList<>();
        for (Instance i : instances.values()) {
            boolean idle = (i.state == State.ACTIVE || i.state == State.CLEARED) && i.emptySince >= 0 && now - i.emptySince >= idleMillis;
            boolean exited = i.state == State.CLEARED && now - i.clearedAt >= exitDelayMillis;
            boolean noMembers = (i.state == State.ACTIVE || i.state == State.CLEARED) && i.members.isEmpty();
            if (idle || exited || noMembers) {
                i.state = State.CLOSING;
                out.add(i);
            }
        }
        return out;
    }

    public void forceClose(String id) {
        Instance i = instances.get(id);
        if (i != null) i.state = State.CLOSING;
    }

    /** 월드 정리까지 끝나면 호출. */
    public void remove(String id) {
        instances.remove(id);
    }

    public Optional<Instance> get(String id) {
        return Optional.ofNullable(instances.get(id));
    }

    /** CLOSING이 아닌 인스턴스 중 멤버로 들어 있는 곳. */
    public Optional<Instance> instanceOf(UUID player) {
        return instances.values().stream().filter(i -> i.state != State.CLOSING && i.members.contains(player)).findFirst();
    }

    public int openCount() {
        return instances.size();
    }

    public Collection<Instance> all() {
        return List.copyOf(instances.values());
    }
}
