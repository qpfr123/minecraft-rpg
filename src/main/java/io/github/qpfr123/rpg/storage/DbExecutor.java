package io.github.qpfr123.rpg.storage;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/** DB 전용 단일 스레드. 작업은 제출 순서대로 실행된다. 메인 스레드는 결과를 기다리지 않는다. */
public final class DbExecutor {
    @FunctionalInterface
    public interface DbTask<T> { T run(Database db) throws Exception; }

    private final Database db;
    private final Logger log;
    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "MinecraftRPG-DB");
        t.setDaemon(false);
        return t;
    });

    public DbExecutor(Database db, Logger log) {
        this.db = db;
        this.log = log;
    }

    public <T> CompletableFuture<T> submit(String what, DbTask<T> task) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return task.run(db);
            } catch (Exception e) {
                log.log(Level.SEVERE, "DB task failed: " + what, e);
                throw new java.util.concurrent.CompletionException(e);
            }
        }, exec);
    }

    /** 대기 중인 쓰기를 모두 끝내고 닫는다. */
    public void shutdown() {
        exec.shutdown();
        try {
            if (!exec.awaitTermination(30, TimeUnit.SECONDS)) {
                log.severe("DB executor did not finish within 30s");
            }
            db.close();
        } catch (Exception e) {
            log.log(Level.SEVERE, "DB shutdown failed", e);
        }
    }
}
