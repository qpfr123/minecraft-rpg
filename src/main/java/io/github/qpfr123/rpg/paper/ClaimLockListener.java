package io.github.qpfr123.rpg.paper;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Iterator;

/**
 * 수령 확정(CLAIMED 커밋) 전 아이템 잠금. 잠긴 아이템은 인벤토리를 떠날 수 없으므로, 그 사이 서버가 꺼져도
 * 복구 대조가 "지급됨"을 놓치지 않는다. 잠금은 보통 수 밀리초, DB 장애 시에는 복구될 때까지 유지된다.
 */
public final class ClaimLockListener implements Listener {
    private final GearItems gear;

    public ClaimLockListener(GearItems gear) {
        this.gear = gear;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        boolean locked = gear.isLocked(event.getCurrentItem()) || gear.isLocked(event.getCursor());
        if (!locked && event.getHotbarButton() >= 0 && event.getWhoClicked() instanceof Player p) {
            locked = gear.isLocked(p.getInventory().getItem(event.getHotbarButton()));
        }
        if (!locked && event.getClick() == org.bukkit.event.inventory.ClickType.SWAP_OFFHAND
                && event.getWhoClicked() instanceof Player p) {
            locked = gear.isLocked(p.getInventory().getItemInOffHand());
        }
        if (locked) {
            event.setCancelled(true);
            notice(event.getWhoClicked());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (gear.isLocked(event.getOldCursor())) {
            event.setCancelled(true);
            notice(event.getWhoClicked());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (gear.isLocked(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
            notice(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) { // 액자·갑옷 거치대·동물 먹이 등
        ItemStack hand = event.getPlayer().getInventory().getItem(event.getHand());
        if (gear.isLocked(hand)) {
            event.setCancelled(true);
            notice(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (gear.isLocked(event.getItem())) event.setCancelled(true);
    }

    /** 사망해도 잠긴 아이템은 떨어지지 않고 그대로 남는다. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        Iterator<ItemStack> it = event.getDrops().iterator();
        while (it.hasNext()) {
            ItemStack item = it.next();
            if (gear.isLocked(item)) {
                event.getItemsToKeep().add(item);
                it.remove();
            }
        }
    }

    private static void notice(org.bukkit.entity.HumanEntity who) {
        who.sendActionBar(net.kyori.adventure.text.Component.text("보상 확정 중인 아이템은 아직 옮길 수 없습니다.",
                net.kyori.adventure.text.format.NamedTextColor.YELLOW));
    }
}
