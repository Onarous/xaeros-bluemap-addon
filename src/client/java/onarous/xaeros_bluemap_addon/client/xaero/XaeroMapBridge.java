package onarous.xaeros_bluemap_addon.client.xaero;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ChunkPos;
import onarous.xaeros_bluemap_addon.Xaeros_bluemap_addon;
import onarous.xaeros_bluemap_addon.client.bluemap.PrbmParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Bridge to Xaero's World Map.
 * Provides soft-dependency safety: if Xaero is absent, fallback file is written.
 */
public class XaeroMapBridge {

    public static boolean isXaeroPresent() {
        if (FabricLoader.getInstance().isModLoaded("xaeroworldmap")) {
            return true;
        }
        try {
            Class.forName("xaero.map.WorldMap");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    public static int revealChunks(Map<ChunkPos, PrbmParser.ChunkData> explored, Minecraft mc) {
        if (!isXaeroPresent()) return -1;

        // Xaero requires region/tile creation to run on the client main thread
        // (getLeafMapRegion(..., create=true) throws IllegalAccessError otherwise).
        if (!mc.isSameThread()) {
            final int[] result = {0};
            final CountDownLatch latch = new CountDownLatch(1);
            try {
                mc.execute(() -> {
                    try {
                        result[0] = revealChunksOnMain(explored, mc);
                    } finally {
                        latch.countDown();
                    }
                });
            } catch (Exception e) {
                Xaeros_bluemap_addon.LOGGER.error("[XaerosBluemapAddon] Could not dispatch reveal to the main thread", e);
                return 0;
            }
            try {
                if (!latch.await(180, TimeUnit.SECONDS)) {
                    Xaeros_bluemap_addon.LOGGER.warn("[XaerosBluemapAddon] Reveal timed out on the main thread");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return result[0];
        }
        return revealChunksOnMain(explored, mc);
    }

    private static int revealChunksOnMain(Map<ChunkPos, PrbmParser.ChunkData> explored, Minecraft mc) {
        try {
            return XaeroDirectBridge.revealChunksOnMain(explored);
        } catch (Throwable e) {
            Xaeros_bluemap_addon.LOGGER.error("[XaerosBluemapAddon] Direct Xaero reveal failed", e);
            saveFallbackFile(explored.keySet(), mc);
            return 0;
        }
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
}
