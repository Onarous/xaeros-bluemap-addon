package onarous.xaeros_bluemap_addon.client.bluemap;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.world.level.ChunkPos;
import onarous.xaeros_bluemap_addon.Xaeros_bluemap_addon;
import onarous.xaeros_bluemap_addon.config.BluemapSyncConfig;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * HTTP client that talks to a BlueMap web interface.
 *
 * <p>BlueMap hires tile coordinates:<br>
 * Tile (tx, tz) covers blocks (tx*SIZE .. tx*SIZE+SIZE-1, tz*SIZE .. tz*SIZE+SIZE-1).<br>
 * Default SIZE = 32 blocks = 2×2 Minecraft chunks.
 */
public class BluemapApiClient {

    // ── Data records ────────────────────────────────────────────────────────

    /** Metadata returned by the /maps/{id} endpoint. */
    public record MapInfo(
            String id,
            String name,
            double startX,
            double startZ,
            double hiresViewDistance
    ) {}

    // ── Internals ───────────────────────────────────────────────────────────

    private static final Gson GSON = new Gson();

    /** Shared HTTP client (Java 11+, bundled in Java 21+). */
    private final HttpClient http;

    /** Thread pool for parallel tile probing. */
    private final ExecutorService pool;

    private final BluemapSyncConfig cfg;

    public BluemapApiClient(BluemapSyncConfig cfg) {
        this.cfg = cfg;
        this.pool = Executors.newFixedThreadPool(
                Math.max(1, cfg.parallelRequests),
                r -> {
                    Thread t = new Thread(r, "bluemap-tile-probe");
                    t.setDaemon(true);
                    return t;
                });
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .executor(pool)
                .build();
    }

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Lists available map IDs from the BlueMap server.
     * Tries the v1 REST API first, then the legacy /maps/ endpoint.
     */
    public List<String> fetchAvailableMaps() {
        String base = normalise(cfg.bluemapUrl);

        try {
            String body = get(base + "/settings.json");
            JsonObject obj = GSON.fromJson(body, JsonObject.class);
            if (obj != null && obj.has("maps")) {
                JsonArray arr = obj.getAsJsonArray("maps");
                List<String> ids = new ArrayList<>();
                for (JsonElement el : arr) {
                    if (el.isJsonPrimitive()) {
                        ids.add(el.getAsString());
                    } else if (el.isJsonObject() && el.getAsJsonObject().has("id")) {
                        ids.add(el.getAsJsonObject().get("id").getAsString());
                    }
                }
                return ids;
            }
        } catch (Exception e) {
            Xaeros_bluemap_addon.LOGGER.error("[XaerosBluemapAddon] Could not list BlueMap maps", e);
        }

        return Collections.emptyList();
    }

    /**
     * Fetches metadata for a single map (startPos, hiresViewDistance, …).
     */
    public Optional<MapInfo> fetchMapInfo(String mapId) {
        String base = normalise(cfg.bluemapUrl);
        try {
            String body = get(base + "/maps/" + mapId + "/settings.json");
            JsonObject json = GSON.fromJson(body, JsonObject.class);
            if (json == null) return Optional.empty();

            String name = json.has("name") ? json.get("name").getAsString() : mapId;
            double startX = 0, startZ = 0;
            if (json.has("startPos")) {
                JsonArray sp = json.getAsJsonArray("startPos");
                if (sp.size() >= 2) {
                    startX = sp.get(0).getAsDouble();
                    startZ = sp.get(1).getAsDouble();
                }
            }
            // In Bluemap 3.x/4.x/5.x hires view distance isn't really a strict number in settings.json
            // We use cfg.parseRange
            double dist = cfg.parseRange;
            return Optional.of(new MapInfo(mapId, name, startX, startZ, dist));
        } catch (Exception e) {
            Xaeros_bluemap_addon.LOGGER.error("[XaerosBluemapAddon] Failed to fetch map info for '{}'", mapId, e);
            return Optional.empty();
        }
    }

    /**
     * Probes the BlueMap tile grid and returns the map of Minecraft {@link ChunkPos}
     * whose corresponding BlueMap hires tiles exist (HTTP 200).
     *
     * @param info          map info (bounds, tile size)
     * @param progressTick  called every time a tile probe completes
     * @return non-null map of chunk positions that are rendered on BlueMap
     */
    public Map<ChunkPos, PrbmParser.ChunkData> fetchExploredChunks(MapInfo info, Consumer<Integer> progressTick) {
        String base = normalise(cfg.bluemapUrl);
        int tileSize = cfg.hiresBlockSize;

        double dist = info.hiresViewDistance();
        int centerTX = Math.floorDiv((int) info.startX(), tileSize);
        int centerTZ = Math.floorDiv((int) info.startZ(), tileSize);
        int rangeT = (int) Math.ceil(dist / tileSize) + 1;

        List<int[]> candidates = new ArrayList<>();
        for (int tx = centerTX - rangeT; tx <= centerTX + rangeT; tx++) {
            for (int tz = centerTZ - rangeT; tz <= centerTZ + rangeT; tz++) {
                candidates.add(new int[]{tx, tz});
            }
        }

        int limit = cfg.maxTilesPerSync > 0 ? cfg.maxTilesPerSync : Integer.MAX_VALUE;
        if (candidates.size() > limit) {
            candidates = candidates.subList(0, limit);
        }

        Xaeros_bluemap_addon.LOGGER.info("[XaerosBluemapAddon] Probing {} tile positions for map '{}'…",
                candidates.size(), info.id());

        Map<ChunkPos, PrbmParser.ChunkData> result = new ConcurrentHashMap<>();
        AtomicInteger done = new AtomicInteger(0);
        List<CompletableFuture<Void>> futures = new ArrayList<>(candidates.size());

        for (int[] tile : candidates) {
            final int tx = tile[0], tz = tile[1];
            CompletableFuture<Void> f = CompletableFuture.runAsync(() -> {
                String tileUrlBase = base + "/maps/" + info.id() + "/tiles/0/x" + tx + "/z" + tz;
                Map<ChunkPos, PrbmParser.ChunkData> tileData = getPrbm(tileUrlBase, tx, tz, tileSize);
                if (tileData != null) {
                    for (Map.Entry<ChunkPos, PrbmParser.ChunkData> entry : tileData.entrySet()) {
                        result.merge(entry.getKey(), entry.getValue(), (oldData, newData) -> {
                            for (int i = 0; i < 256; i++) {
                                if (newData.heights[i] > oldData.heights[i]) {
                                    oldData.heights[i] = newData.heights[i];
                                    oldData.blocks[i] = newData.blocks[i];
                                }
                            }
                            return oldData;
                        });
                    }
                }
                progressTick.accept(done.incrementAndGet());
            }, pool);
            futures.add(f);
        }

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(600, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            Xaeros_bluemap_addon.LOGGER.warn("[XaerosBluemapAddon] Tile probing timed out; partial results returned.");
        } catch (Exception e) {
            Xaeros_bluemap_addon.LOGGER.error("[XaerosBluemapAddon] Error during tile probing", e);
        }

        return result;
    }

    /** Shut down the thread pool gracefully. */
    public void shutdown() {
        pool.shutdown();
    }

    private Map<ChunkPos, PrbmParser.ChunkData> getPrbm(String urlBase, int tx, int tz, int tileSize) {
        String[] exts = {".prbm", ".prbm.gz"};
        for (String ext : exts) {
            try {
                URL url = new URL(urlBase + ext);
                HttpURLConnection con = (HttpURLConnection) url.openConnection();
                con.setRequestMethod("GET");
                con.setRequestProperty("Accept-Encoding", "gzip");
                con.setConnectTimeout(cfg.tileRequestTimeoutSeconds * 1000);
                con.setReadTimeout(cfg.tileRequestTimeoutSeconds * 1000);

                if (con.getResponseCode() == 200) {
                    try (InputStream in = con.getInputStream()) {
                        return PrbmParser.parse(in, tx, tz, tileSize);
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private String get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .timeout(Duration.ofSeconds(15))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("HTTP " + resp.statusCode() + " for " + url);
        }
        return resp.body();
    }

    private static String normalise(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
