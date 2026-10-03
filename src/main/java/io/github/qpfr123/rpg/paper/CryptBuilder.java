package io.github.qpfr123.rpg.paper;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;

import java.util.Random;

/**
 * 지하 납골당 v2 템플릿. 바닥 y=63, 입구는 남쪽(z 음수)에서 북쪽(+z)으로 진행한다.
 * <pre>
 * 입구 홀(z -5..5) → 회랑 → 구울 방(z 20..36, 기둥 4개)
 *   → 회랑 → 궁수 방(z 48..66, 양옆 2층 회랑과 계단) → 회랑 → 보스 투기장(z 77..103, 기둥·단상)
 * </pre>
 * 전체 영역을 벽 재료로 채운 뒤 방·회랑을 비워 만들고, 장식은 고정 시드 난수로 놓아 항상 같은 맵이 나온다.
 */
final class CryptBuilder {
    private static final int FLOOR = 63;
    private final World w;
    private final Random rnd = new Random(20261003L);

    private CryptBuilder(World w) {
        this.w = w;
    }

    static void build(World w) {
        new CryptBuilder(w).run();
    }

    private void run() {
        // 1. 외곽 덩어리
        for (int x = -16; x <= 16; x++)
            for (int y = FLOOR - 1; y <= 74; y++)
                for (int z = -7; z <= 105; z++) set(x, y, z, wall());

        // 2. 공간 파기(겹치는 곳이 통로가 된다)
        carve(-5, 5, 64, 69, -5, 5);       // 입구 홀
        carve(-2, 2, 64, 67, 6, 19);       // 회랑 1
        carve(-10, 10, 64, 70, 20, 36);    // 구울 방
        carve(-2, 2, 64, 67, 37, 47);      // 회랑 2
        carve(-12, 12, 64, 71, 48, 66);    // 궁수 방
        carve(-2, 2, 64, 67, 67, 76);      // 회랑 3
        carve(-14, 14, 64, 72, 77, 103);   // 보스 투기장

        // 3. 바닥 재질
        floor(-5, 5, -5, 5);
        floor(-2, 2, 6, 19);
        floor(-10, 10, 20, 36);
        floor(-2, 2, 37, 47);
        floor(-12, 12, 48, 66);
        floor(-2, 2, 67, 76);
        floor(-14, 14, 77, 103);

        // 4. 구조물
        for (int[] p : new int[][] {{-6, 24}, {5, 24}, {-6, 31}, {5, 31}}) pillar(p[0], p[1], 64, 70);
        // 궁수 방 2층 회랑(윗면 y=66, 서는 높이 67)과 계단
        solid(-12, -9, 64, 66, 50, 64, Material.POLISHED_DEEPSLATE);
        solid(9, 12, 64, 66, 50, 64, Material.POLISHED_DEEPSLATE);
        for (int s : new int[] {-1, 1}) {
            solid(7 * s, 7 * s, 64, 64, 56, 58, Material.DEEPSLATE_TILES);
            solid(8 * s, 8 * s, 64, 65, 56, 58, Material.DEEPSLATE_TILES);
        }
        for (int x : new int[] {-9, 9}) for (int z = 50; z <= 64; z += 2) set(x, 67, z, Material.DEEPSLATE_TILE_WALL);
        // 투기장 기둥과 단상(윗면 y=64, 서는 높이 65)
        for (int[] p : new int[][] {{-10, 82}, {9, 82}, {-10, 97}, {9, 97}}) pillar(p[0], p[1], 64, 72);
        solid(-3, 3, 64, 64, 92, 98, Material.CHISELED_STONE_BRICKS);
        solid(-4, 4, 64, 64, 91, 91, Material.STONE_BRICK_SLAB);

        // 5. 조명·장식
        ceilingLights(-5, 5, -5, 5, 70);
        ceilingLights(-10, 10, 20, 36, 71);
        ceilingLights(-12, 12, 48, 66, 72);
        ceilingLights(-14, 14, 77, 103, 73);
        for (int z = 8; z <= 18; z += 5) hang(0, 67, z);
        for (int z = 39; z <= 46; z += 5) hang(0, 67, z);
        for (int z = 69; z <= 75; z += 5) hang(0, 67, z);
        for (int[] p : new int[][] {{-4, -4}, {4, -4}, {-4, 4}, {4, 4}}) set(p[0], 64, p[1], Material.LANTERN);
        scatter(-10, 10, 20, 36, Material.COBWEB, 0.02);
        scatter(-10, 10, 20, 36, Material.SKELETON_SKULL, 0.01);
        scatter(-12, 12, 48, 66, Material.COBWEB, 0.015);
        scatter(-14, 14, 77, 103, Material.CANDLE, 0.01);
        for (int z = 79; z <= 90; z++) set(0, 64, z, Material.RED_CARPET);
        for (int x = -2; x <= 2; x += 2) set(x, 65, 99, Material.SOUL_LANTERN);
    }

    private Material wall() {
        int r = rnd.nextInt(100);
        return r < 70 ? Material.DEEPSLATE_BRICKS : r < 85 ? Material.CRACKED_DEEPSLATE_BRICKS : Material.DEEPSLATE_TILES;
    }

    private Material floorMat() {
        int r = rnd.nextInt(100);
        return r < 60 ? Material.STONE_BRICKS : r < 85 ? Material.MOSSY_STONE_BRICKS : Material.CRACKED_STONE_BRICKS;
    }

    private void set(int x, int y, int z, Material m) {
        w.getBlockAt(x, y, z).setType(m, false);
    }

    private void carve(int x1, int x2, int y1, int y2, int z1, int z2) {
        solid(x1, x2, y1, y2, z1, z2, Material.AIR);
    }

    private void solid(int x1, int x2, int y1, int y2, int z1, int z2, Material m) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++)
            for (int y = y1; y <= y2; y++)
                for (int z = z1; z <= z2; z++) set(x, y, z, m);
    }

    private void floor(int x1, int x2, int z1, int z2) {
        for (int x = x1; x <= x2; x++) for (int z = z1; z <= z2; z++) set(x, FLOOR, z, floorMat());
    }

    private void pillar(int x, int z, int y1, int y2) {
        solid(x, x + 1, y1, y2, z, z + 1, Material.POLISHED_BLACKSTONE_BRICKS);
        set(x, y1, z, Material.CHISELED_POLISHED_BLACKSTONE);
    }

    private void ceilingLights(int x1, int x2, int z1, int z2, int ceilingY) {
        for (int x = x1 + 2; x <= x2 - 2; x += 5) for (int z = z1 + 2; z <= z2 - 2; z += 5) set(x, ceilingY, z, Material.GLOWSTONE);
    }

    private void hang(int x, int y, int z) {
        BlockData d = Bukkit.createBlockData("minecraft:soul_lantern[hanging=true]");
        w.getBlockAt(x, y, z).setBlockData(d, false);
    }

    /** 빈 바닥 칸에만 확률적으로 놓는다. 통로 가운데(x -1..1)는 비워 둔다. */
    private void scatter(int x1, int x2, int z1, int z2, Material m, double p) {
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                if (Math.abs(x) <= 1) continue;
                if (rnd.nextDouble() >= p) continue;
                if (w.getBlockAt(x, 64, z).getType() == Material.AIR && w.getBlockAt(x, 63, z).getType().isSolid()) set(x, 64, z, m);
            }
        }
    }
}
