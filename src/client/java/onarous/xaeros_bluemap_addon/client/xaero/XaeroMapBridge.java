package onarous.xaeros_bluemap_addon.client.xaero;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ChunkPos;
import onarous.xaeros_bluemap_addon.Xaeros_bluemap_addon;
import onarous.xaeros_bluemap_addon.client.bluemap.PrbmParser;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;

/**
 * Bridge to Xaero's World Map.
 */
public class XaeroMapBridge {

    public static boolean isXaeroPresent() {
        try {
            Class.forName("xaero.map.WorldMap");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    public static int revealChunks(Map<ChunkPos, PrbmParser.ChunkData> explored, Minecraft mc) {
        if (!isXaeroPresent()) return -1;
        try {
            Object session = getCurrentSession();
            Object processor = getMapProcessor(session);
            if (processor == null) throw new IllegalStateException("MapProcessor field not found");
            
            Object mapWorld = getMapWorld(session, processor);
            if (mapWorld == null) throw new IllegalStateException("Could not obtain MapWorld");

            int caveLayer = 0;
            if (mc.level != null) {
                caveLayer = mc.level.dimensionType().minY() >> 4;
            }
            
            Object cl = tryInvoke(processor, "getCurrentCaveLayer");
            if (cl instanceof Integer) {
                caveLayer = (Integer) cl;
            }

            int count = 0;
            for (Map.Entry<ChunkPos, PrbmParser.ChunkData> entry : explored.entrySet()) {
                if (revealOneChunk(processor, mapWorld, caveLayer, entry.getKey(), entry.getValue())) {
                    count++;
                }
            }
            
            Xaeros_bluemap_addon.LOGGER.info(
                "[XaerosBluemapAddon] Marked {}/{} chunks as explored in Xaero's World Map.",
                count, explored.size());
            
            return count;
        } catch (Exception e) {
            Xaeros_bluemap_addon.LOGGER.error("[XaerosBluemapAddon] Reflection-based reveal failed", e);
            saveFallbackFile(explored.keySet(), mc);
            return 0;
        }
    }

    private static Object getCurrentSession() {
        try {
            Class<?> sessionClass = Class.forName("xaero.map.WorldMapSession");
            return tryInvokeStatic(sessionClass, "getCurrentSession");
        } catch (Throwable ignored) {}
        return null;
    }

    private static Object getMapProcessor(Object session) {
        Object processor = null;
        if (session != null) {
            processor = tryInvoke(session, "getMapProcessor");
            if (processor == null) processor = getField(session, "mapProcessor");
        }
        if (processor == null) {
            try {
                Class<?> wmClass = Class.forName("xaero.map.WorldMap");
                Object wm = getStaticField(wmClass, "instance", "INSTANCE");
                if (wm != null) {
                    processor = getField(wm, "mapProcessor", "processor", "mapProcessorThread");
                }
            } catch (Throwable ignored) {}
        }
        return processor;
    }
    
    private static Object getMapWorld(Object session, Object processor) {
        Object mapWorld = null;
        if (session != null) {
            mapWorld = tryInvoke(session, "getMapWorld", "getWorld", "getPlayerData");
            if (mapWorld == null) mapWorld = getField(session, "mapWorld", "currentWorld", "world", "playerData");
        }
        if (mapWorld == null && processor != null) {
            mapWorld = tryInvoke(processor, "getMapWorld", "getWorld");
        }
        return mapWorld;
    }

    private static boolean revealOneChunk(Object processor, Object mapWorld, int caveLayer, ChunkPos chunk, PrbmParser.ChunkData data) {
        try {
            int regionX = Math.floorDiv(chunk.x(), 32);
            int regionZ = Math.floorDiv(chunk.z(), 32);
            int localX  = Math.floorMod(chunk.x(), 32);
            int localZ  = Math.floorMod(chunk.z(), 32);

            Object region = null;
            for (String name : new String[]{"getLeafMapRegion", "getMapRegion"}) {
                region = tryInvoke(processor, name, caveLayer, regionX, regionZ, true);
                if (region != null) break;
            }
            if (region == null) {
                for (String name : new String[]{"getLeafMapRegion", "getRegion", "getOrCreateRegion",
                        "getLeafRegion", "loadRegion", "getMapRegion"}) {
                    region = tryInvoke(mapWorld, name, regionX, regionZ);
                    if (region != null) break;
                }
            }
            if (region == null) return false;

            Object tile = null;
            tile = tryInvoke(processor, "getMapTile", caveLayer, chunk.x(), chunk.z());
            
            if (tile == null && region != null) {
                for (String name : new String[]{"getMapTile", "getTile", "getOrCreateTile", "getMapTileChunk"}) {
                    tile = tryInvoke(region, name, localX, localZ);
                    if (tile != null) break;
                }
            }
            if (tile == null) return false;

            Object tileChunk = tryInvoke(processor, "getMapChunk", caveLayer, chunk.x(), chunk.z());
            if (tileChunk == null && region != null) {
                for (String name : new String[]{"getChunk", "getMapTileChunk"}) {
                    tileChunk = tryInvoke(region, name, localX >> 2, localZ >> 2);
                    if (tileChunk != null) break;
                }
            }
            if (tileChunk != null) {
                tryInvokeVoid(tileChunk, "setChanged", true);
                tryInvokeVoid(tileChunk, "setToUpdateBuffers", true);
                tryInvokeVoid(tileChunk, "setHasHadTerrain");
            }

            boolean success = false;
            for (String name : new String[]{"setVisited", "markVisited", "setExplored",
                    "markExplored", "visit", "explore", "setLoaded"}) {
                if (tryInvokeVoid(tile, name, true)) success = true;
                else if (tryInvokeVoid(tile, name)) success = true;
            }
            for (String name : new String[]{"visited", "explored", "loaded",
                    "hasData", "mHasData", "isLoaded"}) {
                if (setField(tile, name, true)) success = true;
            }

            try {
                Class<?> mapBlockClass = Class.forName("xaero.map.region.MapBlock");
                Method setBlock = tile.getClass().getMethod("setBlock", int.class, int.class, mapBlockClass);
                Method setState = mapBlockClass.getMethod("setState", net.minecraft.world.level.block.state.BlockState.class);
                Method setHeight = mapBlockClass.getMethod("setHeight", int.class);
                Method setTopHeight = mapBlockClass.getMethod("setTopHeight", int.class);
                Method setLight = mapBlockClass.getMethod("setLight", byte.class);
                Method setGlowing = mapBlockClass.getMethod("setGlowing", boolean.class);
                Method setBiome = mapBlockClass.getMethod("setBiome", net.minecraft.resources.ResourceKey.class);

                net.minecraft.resources.ResourceKey<net.minecraft.world.level.biome.Biome> plainsKey = 
                    net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.BIOME, net.minecraft.resources.Identifier.fromNamespaceAndPath("minecraft", "plains"));

                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        int idx = lx + lz * 16;
                        net.minecraft.world.level.block.state.BlockState block = data.blocks[idx];
                        if (block != null) {
                            Object mapBlock = mapBlockClass.getDeclaredConstructor().newInstance();
                            setState.invoke(mapBlock, block);
                            setHeight.invoke(mapBlock, data.heights[idx]);
                            setTopHeight.invoke(mapBlock, data.heights[idx]);
                            setLight.invoke(mapBlock, (byte) 15);
                            setGlowing.invoke(mapBlock, false);
                            setBiome.invoke(mapBlock, plainsKey);
                            
                            setBlock.invoke(tile, lx, lz, mapBlock);
                        }
                    }
                }
                success = true;
            } catch (Exception ignored) { }
            
            return success;
        } catch (Exception ignored) {}
        return false;
    }

    private static void saveFallbackFile(Set<ChunkPos> chunks, Minecraft client) {
        Path out = client.gameDirectory.toPath().resolve("xaeros_bluemap_sync.txt");
        try {
            List<String> lines = new ArrayList<>();
            lines.add("# Chunks found on BlueMap (for manual review)");
            lines.add("# Format: chunkX,chunkZ");
            chunks.stream()
                    .sorted(Comparator.comparingInt((ChunkPos p) -> p.x())
                            .thenComparingInt(p -> p.z()))
                    .forEach(p -> lines.add(p.x() + "," + p.z()));
            Files.write(out, lines, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Xaeros_bluemap_addon.LOGGER.info(
                    "[XaerosBluemapAddon] Fallback: wrote {} chunk positions to {}",
                    chunks.size(), out);
        } catch (IOException e) {
            Xaeros_bluemap_addon.LOGGER.error("[XaerosBluemapAddon] Could not write fallback file", e);
        }
    }

    private static Object tryInvokeStatic(Class<?> clazz, String... methodNames) {
        for (String name : methodNames) {
            for (boolean declared : new boolean[]{false, true}) {
                try {
                    Method m = declared ? clazz.getDeclaredMethod(name) : clazz.getMethod(name);
                    m.setAccessible(true);
                    return m.invoke(null);
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    private static Object getStaticField(Class<?> clazz, String... names) {
        for (String name : names) {
            for (boolean declared : new boolean[]{false, true}) {
                try {
                    Field f = declared ? clazz.getDeclaredField(name) : clazz.getField(name);
                    f.setAccessible(true);
                    Object v = f.get(null);
                    if (v != null) return v;
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    private static Object getField(Object obj, String... names) {
        Class<?> clazz = obj.getClass();
        while (clazz != null) {
            for (String name : names) {
                try {
                    Field f = clazz.getDeclaredField(name);
                    f.setAccessible(true);
                    Object v = f.get(obj);
                    if (v != null) return v;
                } catch (Exception ignored) {}
            }
            clazz = clazz.getSuperclass();
        }
        return null;
    }

    private static boolean setField(Object obj, String name, Object value) {
        Class<?> clazz = obj.getClass();
        while (clazz != null) {
            try {
                Field f = clazz.getDeclaredField(name);
                f.setAccessible(true);
                f.set(obj, value);
                return true;
            } catch (NoSuchFieldException ignored) {
                clazz = clazz.getSuperclass();
            } catch (Exception e) {
                return false;
            }
        }
        return false;
    }

    private static Object tryInvoke(Object obj, String... names) {
        Class<?> clazz = obj.getClass();
        for (String name : names) {
            try {
                Method m = findMethod(clazz, name);
                if (m != null) {
                    m.setAccessible(true);
                    return m.invoke(obj);
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static Object tryInvoke(Object obj, String name, int a, int b) {
        Class<?> clazz = obj.getClass();
        while (clazz != null) {
            try {
                Method m = clazz.getDeclaredMethod(name, int.class, int.class);
                m.setAccessible(true);
                return m.invoke(obj, a, b);
            } catch (NoSuchMethodException ignored) {
                clazz = clazz.getSuperclass();
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private static Object tryInvoke(Object obj, String name, int a, int b, int c) {
        Class<?> clazz = obj.getClass();
        while (clazz != null) {
            try {
                Method m = clazz.getDeclaredMethod(name, int.class, int.class, int.class);
                m.setAccessible(true);
                return m.invoke(obj, a, b, c);
            } catch (NoSuchMethodException ignored) {
                clazz = clazz.getSuperclass();
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private static Object tryInvoke(Object obj, String name, int a, int b, int c, boolean d) {
        Class<?> clazz = obj.getClass();
        while (clazz != null) {
            try {
                Method m = clazz.getDeclaredMethod(name, int.class, int.class, int.class, boolean.class);
                m.setAccessible(true);
                return m.invoke(obj, a, b, c, d);
            } catch (NoSuchMethodException ignored) {
                clazz = clazz.getSuperclass();
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private static boolean tryInvokeVoid(Object obj, String name, boolean... boolArgs) {
        Class<?> clazz = obj.getClass();
        while (clazz != null) {
            try {
                if (boolArgs.length > 0) {
                    Method m = clazz.getDeclaredMethod(name, boolean.class);
                    m.setAccessible(true);
                    m.invoke(obj, boolArgs[0]);
                } else {
                    Method m = clazz.getDeclaredMethod(name);
                    m.setAccessible(true);
                    m.invoke(obj);
                }
                return true;
            } catch (NoSuchMethodException ignored) {
                clazz = clazz.getSuperclass();
            } catch (Exception e) {
                return false;
            }
        }
        return false;
    }

    private static Method findMethod(Class<?> clazz, String name) {
        while (clazz != null) {
            try {
                return clazz.getDeclaredMethod(name);
            } catch (NoSuchMethodException ignored) {
                clazz = clazz.getSuperclass();
            }
        }
        return null;
    }
}
