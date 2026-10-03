package io.github.qpfr123.rpg.party;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 최소 파티(슬라이스 2, 메모리 전용). 초대 → 수락으로 합류, 리더가 나가면 다음 멤버가 리더가 된다.
 * 접속을 끊으면 파티에서 빠진다. 서버 재시작 시 파티는 사라진다.
 */
public final class PartyService {
    public static final int MAX_SIZE = 4;

    public enum Result { OK, ALREADY_IN_PARTY, NOT_LEADER, FULL, NO_INVITE, NOT_IN_PARTY, SELF }

    public static final class Party {
        private final Set<UUID> members = new LinkedHashSet<>();
        private UUID leader;

        public UUID leader() { return leader; }
        public List<UUID> members() { return new ArrayList<>(members); }
    }

    private final Map<UUID, Party> byPlayer = new HashMap<>();
    private final Map<UUID, Party> invites = new HashMap<>(); // 초대받은 사람 → 파티

    public Optional<Party> partyOf(UUID player) {
        return Optional.ofNullable(byPlayer.get(player));
    }

    public Result invite(UUID leader, UUID target) {
        if (leader.equals(target)) return Result.SELF;
        if (byPlayer.containsKey(target)) return Result.ALREADY_IN_PARTY;
        Party p = byPlayer.get(leader);
        if (p == null) {
            p = new Party();
            p.leader = leader;
            p.members.add(leader);
            byPlayer.put(leader, p);
        }
        if (!p.leader.equals(leader)) return Result.NOT_LEADER;
        if (p.members.size() >= MAX_SIZE) return Result.FULL;
        invites.put(target, p);
        return Result.OK;
    }

    public Result accept(UUID target) {
        Party p = invites.remove(target);
        if (p == null || p.members.isEmpty()) return Result.NO_INVITE;
        if (byPlayer.containsKey(target)) return Result.ALREADY_IN_PARTY;
        if (p.members.size() >= MAX_SIZE) return Result.FULL;
        p.members.add(target);
        byPlayer.put(target, p);
        return Result.OK;
    }

    public Result leave(UUID player) {
        Party p = byPlayer.remove(player);
        invites.remove(player);
        if (p == null) return Result.NOT_IN_PARTY;
        p.members.remove(player);
        if (p.members.size() <= 1) { // 혼자 남으면 해산
            for (UUID m : p.members) byPlayer.remove(m);
            p.members.clear();
            invites.values().removeIf(x -> x == p);
        } else if (player.equals(p.leader)) {
            p.leader = p.members.iterator().next();
        }
        return Result.OK;
    }
}
