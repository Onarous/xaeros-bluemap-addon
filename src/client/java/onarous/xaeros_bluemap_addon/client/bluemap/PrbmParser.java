package onarous.xaeros_bluemap_addon.client.bluemap;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;

import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;

/**
 * Ultra-fast, zero-copy PRBM parser with comprehensive overlay filtering and group auto-detection.
 */
public class PrbmParser {

    public static class ChunkData {
        public final BlockState[] blocks = new BlockState[256];
        public final short[] heights = new short[256];

        public ChunkData() {
            Arrays.fill(heights, Short.MIN_VALUE);
        }
    }

    private static volatile Map<BlockState, int[]> PALETTE;

    private static Map<BlockState, int[]> palette() {
        Map<BlockState, int[]> p = PALETTE;
        if (p == null) {
            synchronized (PrbmParser.class) {
                p = PALETTE;
                if (p == null) {
                    p = buildPalette();
                    PALETTE = p;
                }
            }
        }
        return p;
    }

    private static Map<BlockState, int[]> buildPalette() {
        Map<Integer, BlockState> byColor = new HashMap<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            MapColor mapColor = block.defaultMapColor();
            if (mapColor == null || mapColor == MapColor.NONE || mapColor == MapColor.SNOW) continue;
            int rgb = mapColor.col & 0xFFFFFF;
            if (rgb == 0 || rgb == 0xFFFFFF) continue;
            byColor.putIfAbsent(rgb, block.defaultBlockState());
        }
        byColor.put(MapColor.GRASS.col & 0xFFFFFF, Blocks.GRASS_BLOCK.defaultBlockState());
        byColor.put(MapColor.WATER.col & 0xFFFFFF, Blocks.WATER.defaultBlockState());
        byColor.put(MapColor.STONE.col & 0xFFFFFF, Blocks.STONE.defaultBlockState());
        byColor.put(MapColor.DIRT.col & 0xFFFFFF, Blocks.DIRT.defaultBlockState());
        byColor.put(MapColor.SAND.col & 0xFFFFFF, Blocks.SAND.defaultBlockState());
        byColor.put(MapColor.WOOD.col & 0xFFFFFF, Blocks.OAK_PLANKS.defaultBlockState());
        byColor.put(MapColor.PLANT.col & 0xFFFFFF, Blocks.OAK_LEAVES.defaultBlockState());

        Map<BlockState, int[]> result = new HashMap<>();
        for (Map.Entry<Integer, BlockState> entry : byColor.entrySet()) {
            int rgb = entry.getKey();
            result.put(entry.getValue(),
                    new int[]{(rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF});
        }
        return result;
    }

    private static final Map<Integer, BlockState> COLOR_CACHE = new ConcurrentHashMap<>(1024);

    public static BlockState findClosest(int r, int g, int b) {
        int key = ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
        return COLOR_CACHE.computeIfAbsent(key, k -> computeClosest(r, g, b));
    }

    private static BlockState computeClosest(int r, int g, int b) {
        if (r >= 240 && g >= 240 && b >= 240) {
            return Blocks.SNOW_BLOCK.defaultBlockState();
        }
        if (g > r && g > b && (g - r) > 15) {
            return Blocks.GRASS_BLOCK.defaultBlockState();
        }
        if (b > r && b > g && (b - g) > 20) {
            return Blocks.WATER.defaultBlockState();
        }

        BlockState best = Blocks.STONE.defaultBlockState();
        int minDist = Integer.MAX_VALUE;
        for (Map.Entry<BlockState, int[]> entry : palette().entrySet()) {
            int[] c = entry.getValue();
            int dr = c[0] - r;
            int dg = c[1] - g;
            int db = c[2] - b;
            int dist = dr * dr + dg * dg + db * db;
            if (dist < minDist) {
                minDist = dist;
                best = entry.getKey();
            }
        }
        return best;
    }

    /**
     * Fast IdentityHashSet containing non-solid overlays and underwater vegetation that shouldn't hide terrain or water.
     */
    private static final Set<Block> OVERLAY_BLOCKS = Collections.newSetFromMap(new IdentityHashMap<>());

    static {
        Block[] overlays = {
            // Grass and vegetation
            Blocks.SHORT_GRASS, Blocks.TALL_GRASS, Blocks.FERN, Blocks.LARGE_FERN,
            Blocks.GLOW_LICHEN, Blocks.SCULK_VEIN, Blocks.VINE, Blocks.CAVE_VINES,
            Blocks.CAVE_VINES_PLANT, Blocks.PINK_PETALS, Blocks.DEAD_BUSH, Blocks.SUGAR_CANE,
            Blocks.SWEET_BERRY_BUSH, Blocks.HANGING_ROOTS, Blocks.BUSH, Blocks.LEAF_LITTER,
            Blocks.WILDFLOWERS, Blocks.PALE_HANGING_MOSS, Blocks.PALE_MOSS_CARPET, Blocks.MOSS_CARPET,

            // Underwater vegetation (must NOT replace surface water)
            Blocks.SEAGRASS, Blocks.TALL_SEAGRASS, Blocks.KELP, Blocks.KELP_PLANT,
            Blocks.LILY_PAD, Blocks.FROGSPAWN,

            // Flowers
            Blocks.DANDELION, Blocks.POPPY, Blocks.BLUE_ORCHID, Blocks.ALLIUM,
            Blocks.AZURE_BLUET, Blocks.RED_TULIP, Blocks.ORANGE_TULIP, Blocks.WHITE_TULIP,
            Blocks.PINK_TULIP, Blocks.OXEYE_DAISY, Blocks.CORNFLOWER, Blocks.LILY_OF_THE_VALLEY,
            Blocks.SUNFLOWER, Blocks.LILAC, Blocks.ROSE_BUSH, Blocks.PEONY, Blocks.TORCHFLOWER,
            Blocks.PITCHER_PLANT, Blocks.WITHER_ROSE,

            // Lighting & utility
            Blocks.TORCH, Blocks.WALL_TORCH, Blocks.SOUL_TORCH, Blocks.SOUL_WALL_TORCH,
            Blocks.REDSTONE_TORCH, Blocks.REDSTONE_WALL_TORCH, Blocks.REDSTONE_WIRE, Blocks.LEVER,
            Blocks.TRIPWIRE, Blocks.TRIPWIRE_HOOK, Blocks.RAIL, Blocks.POWERED_RAIL,
            Blocks.DETECTOR_RAIL, Blocks.ACTIVATOR_RAIL, Blocks.LANTERN, Blocks.SOUL_LANTERN,
            Blocks.SCAFFOLDING,

            // Air
            Blocks.AIR, Blocks.VOID_AIR, Blocks.CAVE_AIR
        };
        Collections.addAll(OVERLAY_BLOCKS, overlays);

        // Add any additional dynamic blocks if present
        addIfExists("minecraft:dry_vegetation");
        addIfExists("minecraft:short_dry_grass");
        addIfExists("minecraft:tall_dry_grass");
        addIfExists("minecraft:chain");
    }

    private static void addIfExists(String idStr) {
        try {
            Identifier id = Identifier.tryParse(idStr);
            if (id != null) {
                BuiltInRegistries.BLOCK.getOptional(id).ifPresent(OVERLAY_BLOCKS::add);
            }
        } catch (Exception ignored) {}
    }

    private static boolean isOverlayBlock(BlockState state) {
        if (state == null) return false;
        return OVERLAY_BLOCKS.contains(state.getBlock());
    }

    public static Map<ChunkPos, ChunkData> parse(InputStream rawIn, int tx, int tz, int tileSize,
                                                 int translateX, int translateZ) throws IOException {
        return parse(rawIn, tx, tz, tileSize, translateX, translateZ, null);
    }

    /**
     * Parses a BlueMap .prbm tile with zero-copy vertex positions and robust group auto-detection.
     */
    public static Map<ChunkPos, ChunkData> parse(InputStream rawIn, int tx, int tz, int tileSize,
                                                 int translateX, int translateZ,
                                                 BlockState[] texturePalette) throws IOException {
        PushbackInputStream pb = new PushbackInputStream(rawIn, 2);
        int b1 = pb.read();
        int b2 = pb.read();
        pb.unread(b2);
        pb.unread(b1);
        InputStream in = (b1 == 0x1F && b2 == 0x8B) ? new GZIPInputStream(pb) : pb;

        byte[] allData = in.readAllBytes();
        if (allData.length < 8) return Collections.emptyMap();

        int numValues = read3(allData, 2);
        if (numValues <= 0) return Collections.emptyMap();

        int offset = 8;
        int posDataOffset = -1;
        int colorDataOffset = -1;

        while (offset < allData.length) {
            int headerStart = offset;
            StringBuilder sb = new StringBuilder();
            while (offset < allData.length && allData[offset] != 0 && (offset - headerStart) < 32) {
                sb.append((char) allData[offset++]);
            }
            if (offset >= allData.length || allData[offset] != 0) break;
            offset++;
            offset++;
            int pad = (-offset) & 3;
            offset += pad;

            String name = sb.toString();
            int bCount;
            if ("position".equals(name)) {
                posDataOffset = offset;
                bCount = 12;
            } else if ("normal".equals(name)) {
                bCount = 3;
            } else if ("color".equals(name)) {
                colorDataOffset = offset;
                bCount = 3;
            } else if ("uv".equals(name)) {
                bCount = 8;
            } else if ("ao".equals(name)) {
                bCount = 1;
            } else if ("blocklight".equals(name)) {
                bCount = 1;
            } else if ("sunlight".equals(name)) {
                bCount = 1;
            } else {
                break;
            }
            offset += numValues * bCount;
        }

        if (posDataOffset < 0) return Collections.emptyMap();

        Map<ChunkPos, ChunkData> chunkMap = new HashMap<>();

        // Material groups begin at 4-byte aligned offset after attributes
        int rem = (offset + 3) & ~3;
        boolean parsedGroups = false;

        if (texturePalette != null && texturePalette.length > 0 && rem + 12 <= allData.length) {
            int f0 = readIntLE(allData, rem);
            int f1 = readIntLE(allData, rem + 4);
            int f2 = readIntLE(allData, rem + 8);

            // Auto-detect layout: [matIndex, start(0), count] vs [start(0), count, matIndex]
            boolean matFirst = (f1 == 0 && f2 > 0);

            int curRem = rem;
            while (curRem + 12 <= allData.length) {
                int a = readIntLE(allData, curRem);
                int b = readIntLE(allData, curRem + 4);
                int c = readIntLE(allData, curRem + 8);

                int start = matFirst ? b : a;
                int count = matFirst ? c : b;
                int matIndex = matFirst ? a : c;

                if (start < 0 || start >= numValues || count < 0 || (start + count) > numValues) {
                    break;
                }

                BlockState blockState = (matIndex >= 0 && matIndex < texturePalette.length && texturePalette[matIndex] != null)
                        ? texturePalette[matIndex]
                        : Blocks.STONE.defaultBlockState();

                boolean isOverlay = isOverlayBlock(blockState);

                for (int i = start; i < start + count && i < numValues; i++) {
                    int pBase = posDataOffset + i * 12;
                    float x = Float.intBitsToFloat(readIntLE(allData, pBase));
                    float y = Float.intBitsToFloat(readIntLE(allData, pBase + 4));
                    float z = Float.intBitsToFloat(readIntLE(allData, pBase + 8));

                    int bx = translateX + (int) Math.floor(x) + tx * tileSize;
                    int by = (int) Math.floor(y);
                    int bz = translateZ + (int) Math.floor(z) + tz * tileSize;

                    ChunkPos pos = new ChunkPos(bx >> 4, bz >> 4);
                    ChunkData data = chunkMap.computeIfAbsent(pos, k -> new ChunkData());

                    int lx = bx & 15;
                    int lz = bz & 15;
                    int idx = lx + lz * 16;

                    if (isOverlay) {
                        if (data.blocks[idx] == null) {
                            data.heights[idx] = (short) by;
                            data.blocks[idx] = blockState;
                        }
                    } else {
                        // Solid blocks or water override existing if higher or if previous was an overlay
                        if (by > data.heights[idx] || isOverlayBlock(data.blocks[idx])) {
                            data.heights[idx] = (short) by;
                            data.blocks[idx] = blockState;
                        }
                    }
                }

                parsedGroups = true;
                curRem += 12;
            }
        }

        // Fallback to per-vertex color if material groups weren't parsed
        if (!parsedGroups && colorDataOffset >= 0) {
            for (int i = 0; i < numValues; i++) {
                int pBase = posDataOffset + i * 12;
                float x = Float.intBitsToFloat(readIntLE(allData, pBase));
                float y = Float.intBitsToFloat(readIntLE(allData, pBase + 4));
                float z = Float.intBitsToFloat(readIntLE(allData, pBase + 8));

                int cBase = colorDataOffset + i * 3;
                int r = allData[cBase] & 0xFF;
                int g = allData[cBase + 1] & 0xFF;
                int b = allData[cBase + 2] & 0xFF;

                int bx = translateX + (int) Math.floor(x) + tx * tileSize;
                int by = (int) Math.floor(y);
                int bz = translateZ + (int) Math.floor(z) + tz * tileSize;

                ChunkPos pos = new ChunkPos(bx >> 4, bz >> 4);
                ChunkData data = chunkMap.computeIfAbsent(pos, k -> new ChunkData());

                int lx = bx & 15;
                int lz = bz & 15;
                int idx = lx + lz * 16;

                if (by > data.heights[idx]) {
                    data.heights[idx] = (short) by;
                    data.blocks[idx] = findClosest(r, g, b);
                }
            }
        }

        return chunkMap;
    }

    private static int readIntLE(byte[] data, int offset) {
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 24);
    }

    private static int read3(byte[] data, int offset) {
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16);
    }
}
