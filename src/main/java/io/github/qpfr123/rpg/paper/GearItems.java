package io.github.qpfr123.rpg.paper;

import io.github.qpfr123.rpg.loot.GearDefinition;
import io.github.qpfr123.rpg.loot.GearRegistry;
import io.github.qpfr123.rpg.loot.GearSlot;
import io.github.qpfr123.rpg.stat.StatBonuses;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/** RPG 장비 아이템 생성·판별. 각 아이템은 장비 ID와 고유 인스턴스 ID를 PDC에 가진다. */
public final class GearItems {
    private final Keys keys;
    private final GearRegistry registry;

    public GearItems(Keys keys, GearRegistry registry) {
        this.keys = keys;
        this.registry = registry;
    }

    public ItemStack create(GearDefinition def, String instanceId) {
        Material material = Material.matchMaterial(def.material());
        if (material == null) throw new IllegalStateException("unknown material " + def.material());
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(def.displayName(), NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text(def.loreLine(), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("RPG 장비 — 바닐라 피해·방어 수치는 쓰지 않음", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)));
        meta.setUnbreakable(true);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_UNBREAKABLE);
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(keys.gearId, PersistentDataType.STRING, def.id());
        pdc.set(keys.gearInstance, PersistentDataType.STRING, instanceId);
        item.setItemMeta(meta);
        return item;
    }

    public Optional<GearDefinition> definitionOf(ItemStack item) {
        if (item == null || item.isEmpty() || !item.hasItemMeta()) return Optional.empty();
        String id = item.getItemMeta().getPersistentDataContainer().get(keys.gearId, PersistentDataType.STRING);
        return id == null ? Optional.empty() : registry.get(id);
    }

    public Optional<String> instanceOf(ItemStack item) {
        if (item == null || item.isEmpty() || !item.hasItemMeta()) return Optional.empty();
        return Optional.ofNullable(item.getItemMeta().getPersistentDataContainer().get(keys.gearInstance, PersistentDataType.STRING));
    }

    /** 착용·손에 든 RPG 장비 보너스 합. 바닐라 장비는 아무것도 주지 않는다. */
    public StatBonuses equippedBonuses(Player player) {
        PlayerInventory inv = player.getInventory();
        StatBonuses total = StatBonuses.NONE;
        total = total.plus(bonusIf(inv.getItemInMainHand(), GearSlot.MAIN_HAND));
        total = total.plus(bonusIf(inv.getItem(EquipmentSlot.HEAD), GearSlot.HEAD));
        total = total.plus(bonusIf(inv.getItem(EquipmentSlot.CHEST), GearSlot.CHEST));
        total = total.plus(bonusIf(inv.getItem(EquipmentSlot.LEGS), GearSlot.LEGS));
        total = total.plus(bonusIf(inv.getItem(EquipmentSlot.FEET), GearSlot.FEET));
        return total;
    }

    private StatBonuses bonusIf(ItemStack item, GearSlot slot) {
        return definitionOf(item).filter(d -> d.slot() == slot).map(GearDefinition::bonuses).orElse(StatBonuses.NONE);
    }

    // ---- 수령 확정 전 잠금 ----

    /** 지급했지만 DB에서 CLAIMED가 확정되지 않은 아이템 표식. 표식이 있는 동안 인벤토리 밖으로 나갈 수 없다. */
    public void markPending(ItemStack item, String claimKey) {
        item.editPersistentDataContainer(pdc -> pdc.set(keys.pendingClaim, PersistentDataType.STRING, claimKey));
    }

    public Optional<String> pendingClaimOf(ItemStack item) {
        if (item == null || item.isEmpty() || !item.hasItemMeta()) return Optional.empty();
        return Optional.ofNullable(item.getItemMeta().getPersistentDataContainer().get(keys.pendingClaim, PersistentDataType.STRING));
    }

    public boolean isLocked(ItemStack item) {
        return pendingClaimOf(item).isPresent();
    }

    /** 표식 해제. claimKey가 null이면 모든 표식을 대상으로, 조건 함수가 true인 것만 푼다. */
    public int unlock(Player player, java.util.function.Predicate<String> shouldUnlock) {
        int n = unlockIn(player.getInventory(), shouldUnlock) + unlockIn(player.getEnderChest(), shouldUnlock);
        ItemStack cursor = player.getItemOnCursor();
        if (pendingClaimOf(cursor).filter(shouldUnlock).isPresent()) {
            cursor.editPersistentDataContainer(pdc -> pdc.remove(keys.pendingClaim));
            player.setItemOnCursor(cursor);
            n++;
        }
        return n;
    }

    private int unlockIn(Inventory inv, java.util.function.Predicate<String> shouldUnlock) {
        int n = 0;
        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (pendingClaimOf(item).filter(shouldUnlock).isPresent()) {
                item.editPersistentDataContainer(pdc -> pdc.remove(keys.pendingClaim));
                inv.setItem(i, item);
                n++;
            }
        }
        return n;
    }

    /** 인벤토리·엔더상자에서 잠긴 아이템들의 수령 키. */
    public Set<String> pendingClaimKeys(Player player) {
        Set<String> out = new java.util.HashSet<>();
        for (Inventory inv : List.of(player.getInventory(), player.getEnderChest())) {
            for (ItemStack item : inv.getContents()) pendingClaimOf(item).ifPresent(out::add);
        }
        return out;
    }

    /** 인벤토리·엔더상자에서 주어진 인스턴스 ID 중 실제로 있는 것. */
    public long countInstances(Player player, Set<String> instanceIds) {
        return countIn(player.getInventory(), instanceIds) + countIn(player.getEnderChest(), instanceIds);
    }

    private long countIn(Inventory inv, Set<String> ids) {
        long n = 0;
        for (ItemStack item : inv.getContents()) {
            if (instanceOf(item).filter(ids::contains).isPresent()) n++;
        }
        return n;
    }

    public static int freeSlots(Player player) {
        int free = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item == null || item.isEmpty()) free++;
        }
        return free;
    }
}
