package onarous.xaeros_bluemap_addon.client.bluemap;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import onarous.xaeros_bluemap_addon.Xaeros_bluemap_addon;
import onarous.xaeros_bluemap_addon.config.BluemapSyncConfig;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;

/**
 * High-performance HTTP client for BlueMap with connection pooling and async pipelining.
 */
public class BluemapApiClient {

    public record MapInfo(
            String id,
            String name,
            double startX,
            double startZ,
            double hiresViewDistance,
            int tileSize,
            int translateX,
            int translateZ,
            BlockState[] texturePalette
    ) {}

    private static final Gson GSON = new Gson();

    private final HttpClient http;
    private final ExecutorService pool;
    private final BluemapSyncConfig cfg;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicReference<String> preferredExtension = new AtomicReference<>(null);

    public BluemapApiClient(BluemapSyncConfig cfg) {
        this.cfg = cfg;
        int threads = Math.max(1, cfg.parallelRequests);
        this.pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "bluemap-worker");
            t.setDaemon(true);
            return t;
        });
        // Do NOT pass custom bounded executor to HttpClient to prevent thread starvation deadlock
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(cfg.tileRequestTimeoutSeconds))
                .build();
    }

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

            double dist = cfg.parseRange;
            int tileSize = cfg.hiresBlockSize;
            int translateX = 0;
            int translateZ = 0;
            if (json.has("hires") && json.get("hires").isJsonObject()) {
                JsonObject hires = json.getAsJsonObject("hires");
                if (hires.has("tileSize") && hires.get("tileSize").isJsonArray()) {
                    JsonArray ts = hires.getAsJsonArray("tileSize");
                    if (ts.size() >= 1 && ts.get(0).isJsonPrimitive()) {
                        tileSize = ts.get(0).getAsInt();
                    }
                }
                if (hires.has("translate") && hires.get("translate").isJsonArray()) {
                    JsonArray tr = hires.getAsJsonArray("translate");
                    if (tr.size() >= 2) {
                        translateX = tr.get(0).getAsInt();
                        translateZ = tr.get(1).getAsInt();
                    }
                }
            }

            BlockState[] palette = fetchTexturePalette(base, mapId);

            return Optional.of(new MapInfo(mapId, name, startX, startZ, dist, tileSize, translateX, translateZ, palette));
        } catch (Exception e) {
            Xaeros_bluemap_addon.LOGGER.error("[XaerosBluemapAddon] Failed to fetch map info for '{}'", mapId, e);
            return Optional.empty();
        }
    }

    private BlockState[] fetchTexturePalette(String base, String mapId) {
        String url = base + "/maps/" + mapId + "/textures.json";
        try {
            String jsonStr = get(url);
            JsonArray arr = GSON.fromJson(jsonStr, JsonArray.class);
            if (arr == null) return new BlockState[0];

            BlockState[] states = new BlockState[arr.size()];
            for (int i = 0; i < arr.size(); i++) {
                try {
                    JsonObject obj = arr.get(i).getAsJsonObject();
                    String resourcePath = obj.has("resourcePath") ? obj.get("resourcePath").getAsString() : null;
                    BlockState state = resolveResourceBlock(resourcePath);

                    if (state == null && obj.has("color") && obj.get("color").isJsonArray()) {
                        JsonArray c = obj.getAsJsonArray("color");
                        if (c.size() >= 3) {
                            int r = (int) (c.get(0).getAsFloat() * 255);
                            int g = (int) (c.get(1).getAsFloat() * 255);
                            int b = (int) (c.get(2).getAsFloat() * 255);
                            state = PrbmParser.findClosest(r, g, b);
                        }
                    }

                    states[i] = (state != null) ? state : Blocks.STONE.defaultBlockState();
                } catch (Exception ignored) {
                    states[i] = Blocks.STONE.defaultBlockState();
                }
            }
            Xaeros_bluemap_addon.LOGGER.info("[XaerosBluemapAddon] Loaded {} block textures from textures.json", states.length);
            return states;
        } catch (Exception e) {
            Xaeros_bluemap_addon.LOGGER.warn("[XaerosBluemapAddon] Could not fetch textures.json: {}", e.getMessage());
            return new BlockState[0];
        }
    }

    private BlockState resolveResourceBlock(String resourcePath) {
        if (resourcePath == null) return null;
        int colon = resourcePath.indexOf(':');
        String ns = (colon >= 0) ? resourcePath.substring(0, colon) : "minecraft";
        String path = (colon >= 0) ? resourcePath.substring(colon + 1) : resourcePath;
        if (path.startsWith("block/")) path = path.substring(6);

        String name = path;

        if (name.startsWith("grass_block")) name = "grass_block";
        else if (name.startsWith("dirt_path")) name = "dirt_path";
        else if (name.startsWith("podzol")) name = "podzol";
        else if (name.startsWith("mycelium")) name = "mycelium";
        else if (name.startsWith("farmland")) name = "farmland";
        else if (name.startsWith("water")) name = "water";
        else if (name.startsWith("lava")) name = "lava";
        else if (name.equals("powder_snow")) name = "powder_snow";
        else if (name.equals("snow_block") || name.equals("snow")) name = "snow_block";
        else if (name.endsWith("_leaves")) {
            // keep leaves names intact
        } else {
            String[] suffixes = {
                "_top", "_bottom", "_side", "_front", "_back", "_end", "_inside",
                "_lit", "_on", "_off", "_stage0", "_stage1", "_stage2", "_stage3",
                "_stage4", "_stage5", "_stage6", "_stage7", "_overlay", "_still", "_flow",
                "_snow", "_moist", "_honey", "_open", "_particle"
            };
            boolean changed = true;
            while (changed) {
                changed = false;
                for (String s : suffixes) {
                    if (name.endsWith(s)) {
                        name = name.substring(0, name.length() - s.length());
                        changed = true;
                        break;
                    }
                }
            }
        }

        try {
            Identifier id = Identifier.tryParse(ns + ":" + name);
            if (id != null) {
                Optional<Block> blockOpt = BuiltInRegistries.BLOCK.getOptional(id);
                if (blockOpt.isPresent() && blockOpt.get() != Blocks.AIR) {
                    return blockOpt.get().defaultBlockState();
                }
            }
        } catch (Exception ignored) {}

        return null;
    }

    public Map<ChunkPos, PrbmParser.ChunkData> fetchExploredChunks(MapInfo info, int centerX, int centerZ,
                                                                   Consumer<Integer> progressTick) {
        String base = normalise(cfg.bluemapUrl);
        int tileSize = info.tileSize();
        int translateX = info.translateX();
        int translateZ = info.translateZ();
        BlockState[] palette = info.texturePalette();

        double dist = info.hiresViewDistance();
        int centerTX = Math.floorDiv(centerX - translateX, tileSize);
        int centerTZ = Math.floorDiv(centerZ - translateZ, tileSize);
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

        Xaeros_bluemap_addon.LOGGER.info("[XaerosBluemapAddon] Probing {} tile positions (tileSize={}, translate=[{},{}]) around ({},{}) for map '{}'…",
                candidates.size(), tileSize, translateX, translateZ, centerX, centerZ, info.id());

        Map<ChunkPos, PrbmParser.ChunkData> result = new ConcurrentHashMap<>(candidates.size() * 2);
        AtomicInteger done = new AtomicInteger(0);
        AtomicInteger found = new AtomicInteger(0);
        AtomicInteger missing = new AtomicInteger(0);
        List<CompletableFuture<Void>> futures = new ArrayList<>(candidates.size());

        for (int[] tile : candidates) {
            if (cancelled.get()) break;
            final int tx = tile[0], tz = tile[1];
            CompletableFuture<Void> f = CompletableFuture.runAsync(() -> {
                if (cancelled.get()) {
                    progressTick.accept(done.incrementAndGet());
                    return;
                }
                String tileUrlBase = base + "/maps/" + info.id() + "/tiles/0/x" + tx + "/z" + tz;
                Map<ChunkPos, PrbmParser.ChunkData> tileData =
                        getPrbm(tileUrlBase, tx, tz, tileSize, translateX, translateZ, palette, found, missing);
                if (tileData != null && !cancelled.get()) {
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
            while (!CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).isDone()) {
                if (cancelled.get()) {
                    futures.forEach(fut -> fut.cancel(true));
                    Xaeros_bluemap_addon.LOGGER.info("[XaerosBluemapAddon] Tile probing cancelled by user.");
                    break;
                }
                Thread.sleep(50);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Xaeros_bluemap_addon.LOGGER.warn("[XaerosBluemapAddon] Tile probing interrupted.");
        } catch (Exception e) {
            Xaeros_bluemap_addon.LOGGER.error("[XaerosBluemapAddon] Error during tile probing", e);
        }

        Xaeros_bluemap_addon.LOGGER.info("[XaerosBluemapAddon] Tile probing finished: {} tiles found, {} tiles missing/empty.",
                found.get(), missing.get());
        return result;
    }

    public void cancel() {
        cancelled.set(true);
        pool.shutdownNow();
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public void shutdown() {
        pool.shutdownNow();
    }

    private Map<ChunkPos, PrbmParser.ChunkData> getPrbm(String urlBase, int tx, int tz, int tileSize,
                                                        int translateX, int translateZ,
                                                        BlockState[] palette,
                                                        AtomicInteger found, AtomicInteger missing) {
        if (cancelled.get()) return null;

        String pref = preferredExtension.get();
        String[] exts = (pref != null)
                ? (pref.equals(".prbm.gz") ? new String[]{".prbm.gz", ".prbm"} : new String[]{".prbm", ".prbm.gz"})
                : new String[]{".prbm", ".prbm.gz"};

        for (String ext : exts) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(urlBase + ext))
                        .GET()
                        .header("Accept-Encoding", "gzip")
                        .timeout(Duration.ofSeconds(cfg.tileRequestTimeoutSeconds))
                        .build();

                HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
                if (resp.statusCode() == 200) {
                    if (pref == null) {
                        preferredExtension.set(ext);
                    }
                    byte[] body = resp.body();
                    if (body != null && body.length > 0) {
                        try (InputStream in = new ByteArrayInputStream(body)) {
                            Map<ChunkPos, PrbmParser.ChunkData> data =
                                    PrbmParser.parse(in, tx, tz, tileSize, translateX, translateZ, palette);
                            found.incrementAndGet();
                            return data;
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        missing.incrementAndGet();
        return null;
    }

    private String get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .header("Accept-Encoding", "gzip")
                .timeout(Duration.ofSeconds(15))
                .build();
        HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("HTTP " + resp.statusCode() + " for " + url);
        }
        byte[] bytes = resp.body();
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0x1F && (bytes[1] & 0xFF) == 0x8B) {
            try (InputStream gis = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
                bytes = gis.readAllBytes();
            }
        }
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String normalise(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
