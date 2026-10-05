package io.github.xhy214.village.ui;

import org.bukkit.*;
import org.bukkit.generator.ChunkGenerator;
import java.util.Random;

public final class FlatVillageGenerator extends ChunkGenerator {
    @Override public ChunkData generateChunkData(World world,Random random,int x,int z,BiomeGrid biome) {
        ChunkData data=createChunkData(world);
        data.setRegion(0,60,0,16,61,16,Material.BEDROCK);
        data.setRegion(0,61,0,16,63,16,Material.DIRT);
        data.setRegion(0,63,0,16,64,16,Material.GRASS_BLOCK);
        return data;
    }
    @Override public boolean shouldGenerateNoise() { return false; }
    @Override public boolean shouldGenerateSurface() { return false; }
    @Override public boolean shouldGenerateCaves() { return false; }
    @Override public boolean shouldGenerateDecorations() { return false; }
    @Override public boolean shouldGenerateMobs() { return false; }
    @Override public boolean shouldGenerateStructures() { return false; }
    @Override public Location getFixedSpawnLocation(World world,Random random) { return new Location(world,0.5,64,5.5,180,0); }
}
