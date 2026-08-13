package onarous.xaeros_bluemap_addon.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import onarous.xaeros_bluemap_addon.Xaeros_bluemap_addon;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Config for the Xaeros–BlueMap sync addon.
 * Stored at .minecraft/config/xaeros_bluemap_addon.json
 */
public class BluemapSyncConfig {

    // ── JSON serialisation ───────────────────────────────────────────────────
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("xaeros_bluemap_addon.json");

    private static BluemapSyncConfig instance;

    // ── Config fields (appear in the JSON file) ──────────────────────────────

    /** Full URL to the BlueMap web interface, e.g. http://your-server:8100 */
    public String bluemapUrl = "http://localhost:8100";

    /**
     * BlueMap map-id to sync, e.g. "world", "world_nether", "world_the_end".
     * You can find available IDs at {bluemapUrl}/api/v1/maps
     */
    public String mapId = "world";

    /**
     * Size of a single BlueMap hires tile in blocks (default 32 in BlueMap's config).
     * Each tile covers (hiresBlockSize × hiresBlockSize) blocks, which equals
     * (hiresBlockSize/16) × (hiresBlockSize/16) Minecraft chunks.
     */
    public int hiresBlockSize = 32;

    /**
     * Maximum number of parallel HTTP requests when probing BlueMap tiles.
     * Lower this if the BlueMap server rate-limits you.
     */
    public int parallelRequests = 40;

    /**
     * Maximum tiles to check in one /bmsync start call (0 = unlimited).
     * Useful for very large maps to avoid extremely long sync times.
     */
    public int maxTilesPerSync = 0;

    /**
     * Seconds to wait for an individual tile HTTP probe before timing out.
     */
    public int tileRequestTimeoutSeconds = 6;

    /**
     * The radius (in blocks) to parse around the map's start position.
     * Overrides the default 300 radius if BlueMap doesn't provide hiresViewDistance.
     */
    public int parseRange = 300;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    public static BluemapSyncConfig getInstance() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    /** Reload from disk. Replaces the in-memory singleton. */
    public static BluemapSyncConfig load() {
        if (Files.exists(CONFIG_PATH)) {
            try (Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
                BluemapSyncConfig cfg = GSON.fromJson(reader, BluemapSyncConfig.class);
                if (cfg != null) {
                    instance = cfg;
                    return cfg;
                }
            } catch (Exception e) {
                Xaeros_bluemap_addon.LOGGER.error("[XaerosBluemapAddon] Failed to load config, using defaults", e);
            }
        }
        // First run – write defaults
        BluemapSyncConfig defaults = new BluemapSyncConfig();
        instance = defaults;
        defaults.save();
        Xaeros_bluemap_addon.LOGGER.info("[XaerosBluemapAddon] Created default config at {}", CONFIG_PATH);
        return defaults;
    }

    /** Persist current state to disk. */
    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(CONFIG_PATH)) {
                GSON.toJson(this, writer);
            }
        } catch (Exception e) {
            Xaeros_bluemap_addon.LOGGER.error("[XaerosBluemapAddon] Failed to save config", e);
        }
    }

    /** Human-readable summary used by /bmsync status. */
    @Override
    public String toString() {
        return String.format("bluemapUrl=%s  mapId=%s  hiresBlockSize=%d  parallelRequests=%d  maxTiles=%s  parseRange=%d",
                bluemapUrl, mapId, hiresBlockSize, parallelRequests,
                maxTilesPerSync == 0 ? "unlimited" : String.valueOf(maxTilesPerSync), parseRange);
    }
}
