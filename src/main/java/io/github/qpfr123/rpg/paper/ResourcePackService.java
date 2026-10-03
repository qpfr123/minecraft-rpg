package io.github.qpfr123.rpg.paper;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * UI 리소스팩 배포. 플러그인 jar 안의 resourcepack.zip을 작은 HTTP 서버로 열고, 접속한 플레이어에게 주소와 SHA-1을 보낸다.
 * 주소에 SHA-1을 넣어 팩이 바뀌면 클라이언트 캐시도 새로 받는다.
 */
public final class ResourcePackService implements Listener {
    /** 팩 식별자(고정): 같은 ID로 다시 보내면 클라이언트가 이전 팩을 교체한다. */
    static final UUID PACK_ID = UUID.nameUUIDFromBytes("minecraftrpg:ui".getBytes(StandardCharsets.UTF_8));

    private final Logger log;
    private final boolean enabled;
    private final int port;
    private final String publicHost;
    private final boolean required;
    private byte[] pack;
    private byte[] sha1;
    private String sha1Hex;
    private HttpServer http;
    private ExecutorService pool;

    public ResourcePackService(ConfigurationSection config, Logger log) {
        this.log = log;
        this.enabled = config == null || config.getBoolean("enabled", true);
        this.port = Integer.getInteger("minecraftrpg.packPort", config == null ? 25580 : config.getInt("port", 25580));
        this.publicHost = config == null ? "" : config.getString("public-host", "");
        this.required = config == null || config.getBoolean("required", true);
    }

    public boolean start(InputStream zip) {
        if (!enabled) {
            log.info("resource pack disabled by config");
            return false;
        }
        try (zip) {
            if (zip == null) throw new IOException("resourcepack.zip missing from plugin jar");
            pack = zip.readAllBytes();
            sha1 = MessageDigest.getInstance("SHA-1").digest(pack);
            sha1Hex = HexFormat.of().formatHex(sha1);
            http = HttpServer.create(new InetSocketAddress(port), 16);
            pool = Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "MinecraftRPG-Pack");
                t.setDaemon(true);
                return t;
            });
            http.setExecutor(pool);
            http.createContext("/", this::serve);
            http.start();
            log.info("resource pack " + sha1Hex + " (" + pack.length + " bytes) served on port " + port);
            return true;
        } catch (IOException | NoSuchAlgorithmException e) {
            log.log(Level.WARNING, "resource pack server not started; UI will fall back to plain text", e);
            stop();
            return false;
        }
    }

    public String sha1Hex() {
        return sha1Hex;
    }

    public void stop() {
        if (http != null) http.stop(0);
        if (pool != null) pool.shutdownNow();
        http = null;
    }

    private void serve(HttpExchange ex) throws IOException {
        try (ex) {
            String path = ex.getRequestURI().getPath();
            if (!"GET".equals(ex.getRequestMethod()) || !path.equals("/pack/" + sha1Hex + ".zip")) {
                ex.sendResponseHeaders(404, -1);
                return;
            }
            ex.getResponseHeaders().set("Content-Type", "application/zip");
            ex.sendResponseHeaders(200, pack.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(pack);
            }
        }
    }

    /** 플레이어가 서버 주소로 입력한 호스트로 내려받기 주소를 만든다(같은 PC·LAN 모두 동작). */
    String urlFor(Player player) {
        String host = publicHost;
        if (host == null || host.isBlank()) {
            InetSocketAddress vh = player.getVirtualHost();
            host = vh == null ? "localhost" : vh.getHostString();
        }
        if (host.contains(":") && !host.startsWith("[")) host = "[" + host + "]";
        return "http://" + host + ":" + port + "/pack/" + sha1Hex + ".zip";
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (http == null) return;
        Player p = event.getPlayer();
        p.setResourcePack(PACK_ID, urlFor(p), sha1,
                Component.text("MinecraftRPG UI(HUD·메뉴 그림) 리소스팩입니다.", NamedTextColor.GOLD), required);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onStatus(PlayerResourcePackStatusEvent event) {
        PlayerResourcePackStatusEvent.Status st = event.getStatus();
        log.info("resource pack " + st + " for " + event.getPlayer().getName());
        switch (st) {
            case DECLINED, FAILED_DOWNLOAD, INVALID_URL, FAILED_RELOAD, DISCARDED -> event.getPlayer().sendMessage(Component.text(
                    "UI 리소스팩을 적용하지 못했습니다(" + st + "). HUD와 메뉴가 깨져 보일 수 있습니다.", NamedTextColor.RED));
            default -> { }
        }
    }
}
