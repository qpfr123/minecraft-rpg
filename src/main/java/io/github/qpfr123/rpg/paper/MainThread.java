package io.github.qpfr123.rpg.paper;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public final class MainThread {
    private final Plugin plugin;

    public MainThread(Plugin plugin) {
        this.plugin = plugin;
    }

    /** DB 작업 결과를 메인 스레드에서 처리한다. 플러그인이 꺼지는 중이면 버린다. */
    public <T> void then(CompletableFuture<T> future, Consumer<T> onSuccess) {
        future.whenComplete((value, error) -> {
            if (error != null || !plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> onSuccess.accept(value));
        });
    }

    public void nextTick(Runnable r) {
        if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, r);
    }
}
