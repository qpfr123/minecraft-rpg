package io.github.qpfr123.rpg.paper;

import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareGrindstoneEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.inventory.ItemStack;

import java.util.EnumSet;
import java.util.Set;

/**
 * 바닐라 메커니즘으로 RPG 보상·장비 체계를 우회하는 길을 막는다.
 * <ul>
 *   <li>RPG 장비는 제작·모루·숫돌·대장장이 작업대 재료가 될 수 없다(표식 제거·합성·복제 방지).</li>
 *   <li>주민 거래·화로·양조대 등 바닐라 작업 칸에 RPG 장비를 넣을 수 없다.</li>
 *   <li>관리자 권한 없는 크리에이티브 복제(가운데 클릭 등)로 RPG 장비를 만들 수 없다.</li>
 *   <li>RPG 몹이 바닐라 변환(익사 좀비화 등)으로 표식을 잃고 다른 몹이 되지 않는다.</li>
 * </ul>
 * RPG 몹의 바닐라 드롭·경험치 제거는 {@link PlayerListener#onMobDeath}에서 한다.
 */
public final class VanillaGuardListener implements Listener {
    private static final Set<InventoryType> BLOCKED_STATIONS = EnumSet.of(
            InventoryType.MERCHANT, InventoryType.FURNACE, InventoryType.BLAST_FURNACE, InventoryType.SMOKER,
            InventoryType.BREWING, InventoryType.ANVIL, InventoryType.GRINDSTONE, InventoryType.SMITHING,
            InventoryType.WORKBENCH, InventoryType.CRAFTER, InventoryType.ENCHANTING, InventoryType.BEACON,
            InventoryType.CARTOGRAPHY, InventoryType.LOOM, InventoryType.STONECUTTER);

    private final GearItems gear;
    private final MobService mobs;

    public VanillaGuardListener(GearItems gear, MobService mobs) {
        this.gear = gear;
        this.mobs = mobs;
    }

    private boolean rpg(ItemStack item) {
        return gear.definitionOf(item).isPresent();
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onCraft(PrepareItemCraftEvent event) {
        for (ItemStack i : event.getInventory().getMatrix()) {
            if (rpg(i)) {
                event.getInventory().setResult(null);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onAnvil(PrepareAnvilEvent event) {
        if (rpg(event.getInventory().getFirstItem()) || rpg(event.getInventory().getSecondItem())) event.setResult(null);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onGrindstone(PrepareGrindstoneEvent event) {
        for (ItemStack i : event.getInventory().getContents()) {
            if (rpg(i)) {
                event.setResult(null);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onSmithing(PrepareSmithingEvent event) {
        for (ItemStack i : event.getInventory().getContents()) {
            if (rpg(i)) {
                event.setResult(null);
                return;
            }
        }
    }

    /** 작업 칸이 있는 바닐라 인벤토리에 RPG 장비를 넣는 클릭 차단(셔프트 클릭·숫자키 포함). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onStationClick(InventoryClickEvent event) {
        if (!BLOCKED_STATIONS.contains(event.getView().getTopInventory().getType())) return;
        if (event.getWhoClicked().getGameMode() == GameMode.CREATIVE && event.getWhoClicked().hasPermission("minecraftrpg.admin")) return;
        boolean intoTop = event.getClickedInventory() == event.getView().getTopInventory();
        boolean moving = rpg(event.getCursor())
                || (event.isShiftClick() && rpg(event.getCurrentItem()))
                || (intoTop && event.getHotbarButton() >= 0 && event.getWhoClicked() instanceof Player p
                    && rpg(p.getInventory().getItem(event.getHotbarButton())));
        if (moving && (intoTop || event.isShiftClick())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onStationDrag(org.bukkit.event.inventory.InventoryDragEvent event) {
        if (!BLOCKED_STATIONS.contains(event.getView().getTopInventory().getType()) || !rpg(event.getOldCursor())) return;
        int topSize = event.getView().getTopInventory().getSize();
        if (event.getRawSlots().stream().anyMatch(s -> s < topSize)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCreative(InventoryCreativeEvent event) {
        if (event.getWhoClicked().hasPermission("minecraftrpg.admin")) return;
        if (rpg(event.getCursor())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTransform(EntityTransformEvent event) {
        if (mobs.profileOf(event.getEntity()).isPresent()) event.setCancelled(true);
    }
}
