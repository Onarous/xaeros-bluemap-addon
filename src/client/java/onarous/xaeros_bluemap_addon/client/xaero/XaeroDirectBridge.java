package onarous.xaeros_bluemap_addon.client.xaero;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import onarous.xaeros_bluemap_addon.Xaeros_bluemap_addon;
import onarous.xaeros_bluemap_addon.client.bluemap.PrbmParser;
import xaero.map.MapProcessor;
import xaero.map.WorldMapSession;
import xaero.map.cache.BlockStateShortShapeCache;
import xaero.map.file.MapSaveLoad;
import xaero.map.file.RegionDetection;
import xaero.map.pool.MapTilePool;
import xaero.map.region.MapBlock;
import xaero.map.region.MapLayer;
import xaero.map.region.MapRegion;
import xaero.map.region.MapTile;
import xaero.map.region.MapTileChunk;
import xaero.map.world.MapDimension;
import xaero.map.world.MapWorld;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Direct typed integration with Xaero's World Map API/classes.
 * Must run on the Minecraft main client thread.
 */
public class XaeroDirectBridge {

    private static final AtomicInteger FAIL_LOG = new AtomicInteger();

    // Biome keys for authentic regional coloring in Xaero's World Map
    private static final ResourceKey<Biome> PLAINS_BIOME = ResourceKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath("minecraft", "plains"));
    private static final ResourceKey<Biome> RIVER_BIOME = ResourceKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath("minecraft", "river"));
    private static final ResourceKey<Biome> OCEAN_BIOME = ResourceKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath("minecraft", "ocean"));
    private static final ResourceKey<Biome> DEEP_OCEAN_BIOME = ResourceKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath("minecraft", "deep_ocean"));
    private static final ResourceKey<Biome> DESERT_BIOME = ResourceKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath("minecraft", "desert"));
    private static final ResourceKey<Biome> BADLANDS_BIOME = ResourceKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath("minecraft", "badlands"));
    private static final ResourceKey<Biome> SNOWY_BIOME = ResourceKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath("minecraft", "snowy_plains"));
    private static final ResourceKey<Biome> FROZEN_PEAKS_BIOME = ResourceKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath("minecraft", "frozen_peaks"));
    private static final ResourceKey<Biome> JAGGED_PEAKS_BIOME = ResourceKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath("minecraft", "jagged_peaks"));
    private static final ResourceKey<Biome> GROVE_BIOME = ResourceKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath("minecraft", "grove"));
    private static final ResourceKey<Biome> TAIGA_BIOME = ResourceKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath("minecraft", "taiga"));
    private static final ResourceKey<Biome> BIRCH_BIOME = ResourceKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath("minecraft", "birch_forest"));
    private static final ResourceKey<Biome> DARK_FOREST_BIOME = ResourceKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath("minecraft", "dark_forest"));
    private static final ResourceKey<Biome> JUNGLE_BIOME = ResourceKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath("minecraft", "jungle"));
    private static final ResourceKey<Biome> SWAMP_BIOME = ResourceKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath("minecraft", "swamp"));
    private static final ResourceKey<Biome> CHERRY_BIOME = ResourceKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath("minecraft", "cherry_grove"));
    private static final ResourceKey<Biome> PALE_GARDEN_BIOME = ResourceKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath("minecraft", "pale_garden"));

    public static int revealChunksOnMain(Map<ChunkPos, PrbmParser.ChunkData> explored) {
        WorldMapSession session = WorldMapSession.getCurrentSession();
        if (session == null) throw new IllegalStateException("WorldMapSession not found");

        MapProcessor processor = session.getMapProcessor();
        if (processor == null) throw new IllegalStateException("MapProcessor not found");

        MapWorld mapWorld = processor.getMapWorld();
        if (mapWorld == null) throw new IllegalStateException("MapWorld not found");

        MapDimension mapDimension = mapWorld.getCurrentDimension();
        if (mapDimension == null) throw new IllegalStateException("MapDimension not found");

        int caveLayer = processor.getCurrentCaveLayer();

        MapLayer mapLayer = (mapDimension.getLayeredMapRegions() != null)
                ? mapDimension.getLayeredMapRegions().getLayer(caveLayer)
                : null;

        BlockStateShortShapeCache cache = processor.getBlockStateShortShapeCache();
        MapTilePool tilePool = processor.getTilePool();
        MapSaveLoad saveLoad = processor.getMapSaveLoad();

        Set<MapRegion> touchedRegions = new HashSet<>();
        Set<MapTileChunk> touchedChunks = new HashSet<>();

        int count = 0;
        int newlyCreatedChunks = 0;

        for (Map.Entry<ChunkPos, PrbmParser.ChunkData> entry : explored.entrySet()) {
            ChunkPos chunk = entry.getKey();
            PrbmParser.ChunkData data = entry.getValue();

            try {
                int cx = chunk.x();
                int cz = chunk.z();
                int mtcX = cx >> 2;
                int mtcZ = cz >> 2;
                int regionX = mtcX >> 3;
                int regionZ = mtcZ >> 3;

                // 1. Region
                MapRegion region = processor.getLeafMapRegion(caveLayer, regionX, regionZ, true);
                if (region == null) {
                    if (FAIL_LOG.getAndIncrement() < 5) {
                        Xaeros_bluemap_addon.LOGGER.warn("[XaerosBluemapAddon] getLeafMapRegion returned null for region ({}, {})", regionX, regionZ);
                    }
                    continue;
                }
                touchedRegions.add(region);

                // 2. MapTileChunk (4×4 chunks)
                int chunkIndexX = mtcX & 7;
                int chunkIndexZ = mtcZ & 7;
                MapTileChunk tileChunk = region.getChunk(chunkIndexX, chunkIndexZ);
                if (tileChunk == null) {
                    tileChunk = new MapTileChunk(region, chunkIndexX, chunkIndexZ);
                    region.setChunk(chunkIndexX, chunkIndexZ, tileChunk);
                }
                touchedChunks.add(tileChunk);

                int lx = cx & 3;
                int lz = cz & 3;

                // 3. MapTile (1 chunk = 16×16 blocks)
                MapTile tile = tileChunk.getTile(lx, lz);
                if (tile == null) {
                    newlyCreatedChunks++;
                    if (tilePool != null) {
                        tile = tilePool.get("", cx, cz);
                    }
                    if (tile == null) {
                        tile = new MapTile("", cx, cz);
                    }
                }

                // 4. Fill block data
                fillBlocks(tile, data);
                tile.setLoaded(true);
                tile.setWrittenOnce(true);
                tile.setWorldInterpretationVersion(1);

                // 5. Register tile in chunk
                tileChunk.setTile(lx, lz, tile, cache, processor);

                // 6. Mark chunk as changed and ready for buffer update
                tileChunk.setChanged(true);
                tileChunk.setToUpdateBuffers(true);
                tileChunk.setHasHadTerrain();
                tileChunk.setLoadState((byte) 2);

                count++;
            } catch (Exception e) {
                if (FAIL_LOG.getAndIncrement() < 20) {
                    Xaeros_bluemap_addon.LOGGER.warn("[XaerosBluemapAddon] Failed to reveal chunk ({}, {}): {}",
                            chunk.x(), chunk.z(), e.toString());
                }
            }
        }

        // 7. Enqueue regions for rendering and disk saving
        long now = System.currentTimeMillis();

        for (MapRegion region : touchedRegions) {
            try {
                region.setBeingWritten(true);
                region.setHasHadTerrain();
                region.setLoadState((byte) 2);
                region.restoreBufferUpdateObjects();

                // Always add to render queue unconditionally
                processor.addToProcess(region);
                region.requestRefresh(processor);

                if (mapLayer != null) {
                    if (!mapLayer.regionDetectionExists(region.getRegionX(), region.getRegionZ())) {
                        RegionDetection detection = new RegionDetection(
                                region.getWorldId(),
                                region.getDimId(),
                                region.getMwId(),
                                region.getRegionX(),
                                region.getRegionZ(),
                                region.getRegionFile(),
                                region.getVersion(),
                                true
                        );
                        mapLayer.addRegionDetection(detection);
                        mapLayer.tryAddingToCompleteRegionDetection(detection);
                    }
                }

                region.setLastSaveTime(0L);
                if (saveLoad != null) {
                    saveLoad.updateSave(region, now, caveLayer);
                }
            } catch (Exception e) {
                Xaeros_bluemap_addon.LOGGER.warn("[XaerosBluemapAddon] Region setup error for ({}, {}): {}",
                        region.getRegionX(), region.getRegionZ(), e.toString());
            }
        }

        if (saveLoad != null) {
            saveLoad.saveAll = true;
        }

        Xaeros_bluemap_addon.LOGGER.info(
                "[XaerosBluemapAddon] Synced {}/{} chunks from BlueMap (caveLayer={}, {} new tiles created, {} regions, {} chunks touched).",
                count, explored.size(), caveLayer, newlyCreatedChunks, touchedRegions.size(), touchedChunks.size());

        return count;
    }

    /**
     * Fills all 256 blocks of the tile with per-block authentic biome and color attribution.
     */
    private static void fillBlocks(MapTile tile, PrbmParser.ChunkData data) {
        Map<BlockState, Integer> counts = new HashMap<>();
        int sumH = 0;
        int countH = 0;

        for (int i = 0; i < 256; i++) {
            BlockState b = data.blocks[i];
            if (b != null) {
                counts.merge(b, 1, Integer::sum);
                sumH += data.heights[i];
                countH++;
            }
        }

        BlockState fallbackState = Blocks.GRASS_BLOCK.defaultBlockState();
        if (!counts.isEmpty()) {
            fallbackState = counts.entrySet().stream().max(Map.Entry.comparingByValue()).get().getKey();
        }
        int fallbackHeight = (countH > 0) ? (sumH / countH) : 64;

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int idx = lx + lz * 16;
                BlockState block = data.blocks[idx];
                int h = data.heights[idx];

                if (block == null) {
                    block = fallbackState;
                    h = fallbackHeight;
                }

                ResourceKey<Biome> blockBiome = getBlockBiomeKey(block, h);

                MapBlock mapBlock = new MapBlock();
                mapBlock.setState(block);
                mapBlock.setHeight(h);
                mapBlock.setTopHeight(h);
                mapBlock.setLight((byte) 15);
                mapBlock.setGlowing(false);
                mapBlock.setBiome(blockBiome);
                mapBlock.setSlopeUnknown(true);

                tile.setBlock(lx, lz, mapBlock);
            }
        }
    }

    private static ResourceKey<Biome> getBlockBiomeKey(BlockState state, int h) {
        if (state == null) return PLAINS_BIOME;
        Block b = state.getBlock();

        // 1. Water bodies (rivers vs ocean)
        if (b == Blocks.WATER) {
            if (h <= 55) return DEEP_OCEAN_BIOME;
            if (h <= 62) return RIVER_BIOME;
            return RIVER_BIOME;
        }

        // 2. Snow / Ice / Cold Mountains
        if (b == Blocks.SNOW || b == Blocks.SNOW_BLOCK || b == Blocks.POWDER_SNOW || b == Blocks.ICE || b == Blocks.PACKED_ICE || b == Blocks.BLUE_ICE) {
            return (h > 110) ? FROZEN_PEAKS_BIOME : SNOWY_BIOME;
        }

        // 3. Desert & Badlands
        if (b == Blocks.RED_SAND || b == Blocks.TERRACOTTA || b.getDescriptionId().contains("terracotta")) {
            return BADLANDS_BIOME;
        }
        if (b == Blocks.SAND || b == Blocks.SANDSTONE || b == Blocks.RED_SANDSTONE || b == Blocks.CACTUS) {
            return DESERT_BIOME;
        }

        // 4. Forests & Vegetation
        if (b == Blocks.SPRUCE_LEAVES || b == Blocks.SPRUCE_LOG || b == Blocks.SPRUCE_WOOD || b == Blocks.PODZOL) {
            return (h > 100) ? GROVE_BIOME : TAIGA_BIOME;
        }
        if (b == Blocks.BIRCH_LEAVES || b == Blocks.BIRCH_LOG || b == Blocks.BIRCH_WOOD) {
            return BIRCH_BIOME;
        }
        if (b == Blocks.DARK_OAK_LEAVES || b == Blocks.DARK_OAK_LOG || b == Blocks.DARK_OAK_WOOD) {
            return DARK_FOREST_BIOME;
        }
        if (b == Blocks.JUNGLE_LEAVES || b == Blocks.JUNGLE_LOG || b == Blocks.BAMBOO || b == Blocks.BAMBOO_BLOCK) {
            return JUNGLE_BIOME;
        }
        if (b == Blocks.MANGROVE_LEAVES || b == Blocks.MANGROVE_LOG || b == Blocks.MUD || b == Blocks.MUDDY_MANGROVE_ROOTS) {
            return SWAMP_BIOME;
        }
        if (b == Blocks.CHERRY_LEAVES || b == Blocks.CHERRY_LOG || b == Blocks.PINK_PETALS) {
            return CHERRY_BIOME;
        }
        if (b == Blocks.PALE_OAK_LEAVES || b == Blocks.PALE_OAK_LOG || b == Blocks.PALE_MOSS_BLOCK) {
            return PALE_GARDEN_BIOME;
        }

        // 5. Mountain peaks (stony / jagged)
        if (h > 130 && (b == Blocks.STONE || b == Blocks.GRAVEL)) {
            return JAGGED_PEAKS_BIOME;
        }

        return PLAINS_BIOME;
    }
}
