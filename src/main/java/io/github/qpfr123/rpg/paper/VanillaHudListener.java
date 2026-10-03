package io.github.qpfr123.rpg.paper;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

/**
 * 리소스팩이 바닐라 하트·배고픔·경험치바를 숨기고 그 자리에 RPG HUD를 그린다. 보이지 않는 바닐라 수치가
 * 게임에 영향을 주지 않도록 배고픔은 항상 가득, 바닐라 경험치는 0으로 둔다(RPG 경험치는 별도).
 */
public final class VanillaHudListener implements Listener {
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFood(FoodLevelChangeEvent event) {
        if (!(event.getEntity() instanceof Player p)) return;
        if (event.getFoodLevel() < p.getFoodLevel()) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onExp(PlayerExpChangeEvent event) {
        event.setAmount(0);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        reset(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        reset(event.getPlayer());
    }

    private static void reset(Player p) {
        p.setFoodLevel(20);
        p.setSaturation(5);
        p.setLevel(0);
        p.setExp(0);
    }
}
