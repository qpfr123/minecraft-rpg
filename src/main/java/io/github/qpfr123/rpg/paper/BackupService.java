package io.github.qpfr123.rpg.paper;

import io.github.qpfr123.rpg.storage.DbExecutor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import java.util.stream.Stream;

/** SQLite 온라인 백업(VACUUM INTO). 최근 KEEP개만 남긴다. 복원은 서버를 끈 뒤 scripts/restore-db.sh. */
public final class BackupService {
    private static final int KEEP = 20;
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final DbExecutor db;
    private final Path dir;
    private final Logger log;

    public BackupService(DbExecutor db, Path dir, Logger log) {
        this.db = db;
        this.dir = dir;
        this.log = log;
    }

    public CompletableFuture<Path> backup(String reason) {
        Path target = dir.resolve("rpg-" + LocalDateTime.now().format(FMT) + "-" + reason + ".db");
        return db.submit("backup", d -> {
            d.backupTo(target);
            prune();
            log.info("backup written: " + target);
            return target;
        });
    }

    private void prune() throws IOException {
        if (!Files.isDirectory(dir)) return;
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(p -> p.getFileName().toString().endsWith(".db"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString())).toList();
        }
        for (int i = 0; i < files.size() - KEEP; i++) Files.deleteIfExists(files.get(i));
    }
}
