package io.github.qpfr123.rpg.loot;

import java.util.List;
import java.util.UUID;

/**
 * 보상 원장 한 줄. (eventId, recipient)가 유일하다.
 * 아이템은 장비 인스턴스 ID 단위로 지급하므로, 지급 중 종료 후 인벤토리에서 인스턴스 ID로 실제 지급 여부를 대조할 수 있다.
 */
public record RewardGrant(String eventId, UUID recipient, long exp, List<String> gearIds, Status status, long createdAt) {
    public enum Status { PENDING, CLAIMING, CLAIMED }

    public RewardGrant {
        gearIds = List.copyOf(gearIds);
    }

    public String instanceId(int index) {
        return eventId + "/" + recipient + "/" + index;
    }

    public RewardGrant withStatus(Status s) {
        return new RewardGrant(eventId, recipient, exp, gearIds, s, createdAt);
    }
}
