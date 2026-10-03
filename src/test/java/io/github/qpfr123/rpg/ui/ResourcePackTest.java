package io.github.qpfr123.rpg.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 리소스팩 파일과 코드의 약속(Glyphs, IconModels)이 맞는지, 파일 구조가 클라이언트 규칙에 맞는지 검사한다. */
class ResourcePackTest {
    private static final Path ROOT = Path.of("src/main/resourcepack");

    private static JsonObject json(String rel) throws IOException {
        return JsonParser.parseString(Files.readString(ROOT.resolve(rel))).getAsJsonObject();
    }

    private static BufferedImage png(String namespacedTexture) throws IOException {
        String[] p = namespacedTexture.split(":", 2);
        return ImageIO.read(ROOT.resolve("assets/" + p[0] + "/textures/" + p[1]).toFile());
    }

    @Test
    void packMetaTargets1_21_11() throws IOException {
        JsonObject pack = json("pack.mcmeta").getAsJsonObject("pack");
        assertEquals(75, pack.get("min_format").getAsInt());
        assertEquals(75, pack.get("max_format").getAsInt());
    }

    @Test
    void everyJsonParses() throws IOException {
        try (Stream<Path> s = Files.walk(ROOT)) {
            for (Path p : s.filter(f -> f.toString().endsWith(".json") || f.toString().endsWith(".mcmeta")).toList()) {
                JsonParser.parseString(Files.readString(p));
            }
        }
    }

    @Test
    void bitmapFontsMatchTexturesAndGlyphConstants() throws IOException {
        for (String font : List.of("hud", "hud_text", "hud_points", "gui")) {
            for (JsonElement e : json("assets/rpg/font/" + font + ".json").getAsJsonArray("providers")) {
                JsonObject p = e.getAsJsonObject();
                assertEquals("bitmap", p.get("type").getAsString());
                int ascent = p.get("ascent").getAsInt();
                int height = p.has("height") ? p.get("height").getAsInt() : 8;
                assertTrue(ascent <= height, font + ": ascent must not exceed height");
                String file = p.get("file").getAsString();
                if (file.startsWith("minecraft:")) continue; // 바닐라 ascii.png
                BufferedImage img = png(file);
                JsonArray rows = p.getAsJsonArray("chars");
                int cols = rows.get(0).getAsString().length();
                assertEquals(0, img.getHeight() % rows.size(), file + " rows");
                assertEquals(0, img.getWidth() % cols, file + " cols");
            }
        }
        JsonArray hud = json("assets/rpg/font/hud.json").getAsJsonArray("providers");
        assertEquals((char) Glyphs.HP_BASE, firstChar(hud, 0));
        assertEquals((char) Glyphs.MP_BASE, firstChar(hud, 1));
        assertEquals((char) Glyphs.ORB, firstChar(hud, 2));
        assertEquals(Glyphs.BAR_STATES, hud.get(0).getAsJsonObject().getAsJsonArray("chars").size());
        JsonArray gui = json("assets/rpg/font/gui.json").getAsJsonArray("providers");
        assertEquals(Glyphs.GUI_MENU, firstChar(gui, 0));
        assertEquals(Glyphs.GUI_STATS, firstChar(gui, 1));
        assertEquals(Glyphs.GUI_EQUIPMENT, firstChar(gui, 2));
    }

    private static char firstChar(JsonArray providers, int i) {
        return providers.get(i).getAsJsonObject().getAsJsonArray("chars").get(0).getAsString().charAt(0);
    }

    /** 글리프 진행폭은 그림의 가장 오른쪽 불투명 열로 정해진다. HUD 배치가 가정한 폭과 같아야 한다. */
    @Test
    void glyphWidthsMatchLayoutAssumptions() throws IOException {
        assertEquals(Glyphs.BAR_WIDTH, opaqueWidth(png("rpg:font/hud_hp.png"), 0, 9));
        assertEquals(Glyphs.BAR_WIDTH, opaqueWidth(png("rpg:font/hud_mp.png"), 0, 9));
        assertEquals(Glyphs.ORB_WIDTH, opaqueWidth(png("rpg:font/hud_orb.png"), 0, 22));
        for (String g : List.of("menu", "stats", "equipment")) {
            BufferedImage img = png("rpg:font/gui_" + g + ".png");
            assertEquals(Glyphs.GUI_WIDTH, img.getWidth());
            assertEquals(222, img.getHeight());
            assertEquals(Glyphs.GUI_WIDTH, opaqueWidth(img, 0, 222));
        }
    }

    private static int opaqueWidth(BufferedImage img, int y0, int h) {
        int w = 0;
        for (int y = y0; y < y0 + h; y++) {
            for (int x = 0; x < img.getWidth(); x++) if ((img.getRGB(x, y) >>> 24) != 0) w = Math.max(w, x + 1);
        }
        return w;
    }

    @Test
    void spaceFontMatchesGlyphs() throws IOException {
        JsonObject adv = json("assets/rpg/font/space.json").getAsJsonArray("providers").get(0).getAsJsonObject().getAsJsonObject("advances");
        for (int i = 0; i < Glyphs.SPACE_BITS; i++) {
            assertEquals(-(1 << i), adv.get(String.valueOf((char) (Glyphs.SPACE_NEG + i))).getAsInt());
            assertEquals(1 << i, adv.get(String.valueOf((char) (Glyphs.SPACE_POS + i))).getAsInt());
        }
    }

    @Test
    void everyMenuIconHasItemModelAndTexture() throws IOException {
        for (String m : IconModels.ALL) {
            String model = json("assets/rpg/items/" + m + ".json").getAsJsonObject("model").get("model").getAsString();
            String tex = json("assets/rpg/models/" + model.split(":")[1] + ".json").getAsJsonObject("textures").get("layer0").getAsString();
            BufferedImage img = png(tex + ".png");
            assertEquals(16, img.getWidth(), m);
        }
    }

    @Test
    void vanillaHudSpritesAreHidden() throws IOException {
        for (String s : List.of("heart/full", "heart/container", "heart/half", "food_full", "armor_full", "experience_bar_progress")) {
            BufferedImage img = ImageIO.read(ROOT.resolve("assets/minecraft/textures/gui/sprites/hud/" + s + ".png").toFile());
            assertEquals(0, opaqueWidth(img, 0, img.getHeight()), s + " must be transparent");
        }
    }
}
