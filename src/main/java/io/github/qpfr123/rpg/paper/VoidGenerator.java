package io.github.qpfr123.rpg.paper;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.generator.ChunkGenerator;

import java.util.Random;

/**
 * 아무것도 생성하지 않는 생성기. 던전 템플릿·인스턴스·편집 월드용.
 * 고정 스폰을 주지 않으면 새 월드를 만들 때 서버가 빈 청크를 뒤지며 스폰 지점을 찾느라 메인 스레드가 10초 넘게 멈춘다.
 */
public final class VoidGenerator extends ChunkGenerator {
    @Override
    public Location getFixedSpawnLocation(World world, Random random) {
        return new Location(world, 0.5, 64, 0.5);
    }
}
