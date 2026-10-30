package com.tianyaworld.pmxxlclaims.client;

import net.minecraft.block.BlockState;
import net.minecraft.block.MapColor;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

import java.util.LinkedHashMap;
import java.util.Map;

public final class ChunkColorCache {

    private static final int MAX_ENTRIES = 4096;

    private static final Map<String, Integer> CACHE = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Integer> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    private ChunkColorCache() {
    }

    public static int color(ClientWorld world, int chunkX, int chunkZ) {
        String key = world.getRegistryKey().getValue() + "|" + chunkX + "|" + chunkZ;
        Integer cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        int color = sample(world, chunkX, chunkZ);
        CACHE.put(key, color);
        return color;
    }

    private static int sample(ClientWorld world, int chunkX, int chunkZ) {
        int red = 0;
        int green = 0;
        int blue = 0;
        int count = 0;
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                int blockX = (chunkX << 4) + 2 + i * 4;
                int blockZ = (chunkZ << 4) + 2 + j * 4;
                if (!world.getChunkManager().isChunkLoaded(blockX >> 4, blockZ >> 4)) {
                    continue;
                }
                int top = world.getTopY(Heightmap.Type.WORLD_SURFACE, blockX, blockZ) - 1;
                if (top < world.getBottomY()) {
                    continue;
                }
                BlockPos pos = new BlockPos(blockX, top, blockZ);
                BlockState state = world.getBlockState(pos);
                if (state.isAir()) {
                    continue;
                }
                MapColor mapColor = state.getMapColor(world, pos);
                if (mapColor == null) {
                    continue;
                }
                int rgb = mapColor.color;
                red += (rgb >> 16) & 0xFF;
                green += (rgb >> 8) & 0xFF;
                blue += rgb & 0xFF;
                count++;
            }
        }
        if (count == 0) {
            return 0xFF2B2B2B;
        }
        return 0xFF000000 | ((red / count) << 16) | ((green / count) << 8) | (blue / count);
    }

    public static void clear() {
        CACHE.clear();
    }
}
