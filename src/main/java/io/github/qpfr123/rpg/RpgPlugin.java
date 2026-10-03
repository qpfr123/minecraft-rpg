package io.github.qpfr123.rpg;

import org.bukkit.plugin.java.JavaPlugin;

public final class RpgPlugin extends JavaPlugin {
    @Override
    public void onEnable() {
        getLogger().info("MinecraftRPG " + getPluginMeta().getVersion() + " enabled");
    }

    @Override
    public void onDisable() {
        getLogger().info("MinecraftRPG disabled");
    }
}
