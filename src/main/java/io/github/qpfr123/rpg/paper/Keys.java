package io.github.qpfr123.rpg.paper;

import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;

/** PDC 키. PDC는 아이템·몹의 종류/소유 ID 표식에만 쓴다(플레이어 성장 데이터는 SQLite). */
public final class Keys {
    public final NamespacedKey gearId;
    public final NamespacedKey gearInstance;
    public final NamespacedKey mobProfile;
    public final NamespacedKey mobHp;
    public final NamespacedKey moveSpeed;
    public final NamespacedKey pendingClaim;

    public Keys(Plugin plugin) {
        gearId = new NamespacedKey(plugin, "gear_id");
        gearInstance = new NamespacedKey(plugin, "gear_instance");
        mobProfile = new NamespacedKey(plugin, "mob_profile");
        mobHp = new NamespacedKey(plugin, "mob_hp");
        moveSpeed = new NamespacedKey(plugin, "agility_move_speed");
        pendingClaim = new NamespacedKey(plugin, "pending_claim");
    }
}
