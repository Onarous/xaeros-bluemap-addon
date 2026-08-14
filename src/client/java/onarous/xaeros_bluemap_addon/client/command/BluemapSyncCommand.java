package onarous.xaeros_bluemap_addon.client.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;
import onarous.xaeros_bluemap_addon.Xaeros_bluemap_addon;
import onarous.xaeros_bluemap_addon.client.bluemap.BluemapApiClient;
import onarous.xaeros_bluemap_addon.client.bluemap.PrbmParser;
import onarous.xaeros_bluemap_addon.client.xaero.XaeroMapBridge;
import onarous.xaeros_bluemap_addon.config.BluemapSyncConfig;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/**
 * Registers the {@code /bmsync} client command.
 */
public class BluemapSyncCommand {

    private static volatile boolean syncRunning = false;
    private static volatile BluemapApiClient activeClient = null;

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(
            literal("bmsync")
                .then(literal("start").executes(ctx -> {
                    if (syncRunning) {
                        sendMsg(ctx.getSource(), "§e[BlueMap Sync] A sync is already running, please wait…");
                        return 0;
                    }
                    startSync(ctx.getSource());
                    return 1;
                }))
                .then(literal("stop").executes(ctx -> cancelSync(ctx.getSource())))
                .then(literal("cancel").executes(ctx -> cancelSync(ctx.getSource())))
                .then(literal("status").executes(ctx -> {
                    BluemapSyncConfig cfg = BluemapSyncConfig.getInstance();
                    sendMsg(ctx.getSource(),
                            "§6[BlueMap Sync] §rCurrent config:\n" +
                            "  §aURL:  §f" + cfg.bluemapUrl + "\n" +
                            "  §aMap:  §f" + cfg.mapId + "\n" +
                            "  §aTile size: §f" + cfg.hiresBlockSize + " blocks\n" +
                            "  §aParallel requests: §f" + cfg.parallelRequests + "\n" +
                            "  §aMax tiles: §f" + (cfg.maxTilesPerSync == 0 ? "unlimited" : cfg.maxTilesPerSync) + "\n" +
                            "  §aParse range: §f" + cfg.parseRange + " blocks\n" +
                            "  §aXaero's World Map: §f" + (XaeroMapBridge.isXaeroPresent() ? "§aDetected" : "§cNot found"));
                    return 1;
                }))
                .then(literal("maps").executes(ctx -> {
                    Thread t = new Thread(() -> {
                        BluemapSyncConfig cfg = BluemapSyncConfig.getInstance();
                        sendMsg(ctx.getSource(), "§6[BlueMap Sync] §rFetching map list from " + cfg.bluemapUrl + "…");
                        BluemapApiClient client = new BluemapApiClient(cfg);
                        try {
                            List<String> maps = client.fetchAvailableMaps();
                            if (maps.isEmpty()) {
                                sendMsg(ctx.getSource(), "§c[BlueMap Sync] No maps found. Check the URL.");
                            } else {
                                sendMsg(ctx.getSource(), "§6[BlueMap Sync] §rAvailable maps: §a" + String.join("§r, §a", maps));
                            }
                        } finally {
                            client.shutdown();
                        }
                    }, "bmsync-maps");
                    t.setDaemon(true);
                    t.start();
                    return 1;
                }))
                .then(literal("seturl")
                    .then(argument("url", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            String url = StringArgumentType.getString(ctx, "url").trim();
                            BluemapSyncConfig cfg = BluemapSyncConfig.getInstance();
                            cfg.bluemapUrl = url;
                            cfg.save();
                            sendMsg(ctx.getSource(), "§6[BlueMap Sync] §rURL set to §a" + url);
                            return 1;
                        })))
                .then(literal("setmap")
                    .then(argument("mapId", StringArgumentType.word())
                        .executes(ctx -> {
                            String mapId = StringArgumentType.getString(ctx, "mapId");
                            BluemapSyncConfig cfg = BluemapSyncConfig.getInstance();
                            cfg.mapId = mapId;
                            cfg.save();
                            sendMsg(ctx.getSource(), "§6[BlueMap Sync] §rMap ID set to §a" + mapId);
                            return 1;
                        })))
                .then(literal("setrange")
                    .then(argument("range", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0))
                        .executes(ctx -> {
                            int range = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "range");
                            BluemapSyncConfig cfg = BluemapSyncConfig.getInstance();
                            cfg.parseRange = range;
                            cfg.save();
                            sendMsg(ctx.getSource(), "§6[BlueMap Sync] §rParse range set to §a" + range + " blocks");
                            return 1;
                        })))
                .then(literal("reload").executes(ctx -> {
                    BluemapSyncConfig.load();
                    sendMsg(ctx.getSource(), "§6[BlueMap Sync] §aConfig reloaded from disk.");
                    return 1;
                }))
        );
    }

    private static int cancelSync(FabricClientCommandSource source) {
        BluemapApiClient client = activeClient;
        if (!syncRunning || client == null) {
            sendMsg(source, "§e[BlueMap Sync] No sync is currently running.");
            return 0;
        }
        sendMsg(source, "§6[BlueMap Sync] §eCancelling sync process…");
        client.cancel();
        return 1;
    }

    private static void startSync(FabricClientCommandSource source) {
        BluemapSyncConfig cfg = BluemapSyncConfig.getInstance();

        Thread syncThread = new Thread(() -> {
            syncRunning = true;
            BluemapApiClient apiClient = new BluemapApiClient(cfg);
            activeClient = apiClient;
            long startTime = System.currentTimeMillis();

            try {
                sendMsg(source, "§6[BlueMap Sync] §rConnecting to §a" + cfg.bluemapUrl + "§r…");

                Optional<BluemapApiClient.MapInfo> infoOpt = apiClient.fetchMapInfo(cfg.mapId);
                if (infoOpt.isEmpty()) {
                    if (apiClient.isCancelled()) {
                        sendMsg(source, "§e[BlueMap Sync] Sync was cancelled.");
                        return;
                    }
                    sendMsg(source, "§c[BlueMap Sync] Failed to fetch map info for '" + cfg.mapId + "'. Check URL/mapId.");
                    return;
                }
                BluemapApiClient.MapInfo info = infoOpt.get();
                sendMsg(source, "§6[BlueMap Sync] §rMap '§a" + info.name() + "§r' (radius: §a" +
                        (int) info.hiresViewDistance() + "§r blocks, " + info.texturePalette().length + " textures loaded). Probing tiles…");

                Minecraft mc = Minecraft.getInstance();
                int centerX = (int) info.startX();
                int centerZ = (int) info.startZ();
                if (mc.level != null && mc.player != null) {
                    centerX = mc.player.getBlockX();
                    centerZ = mc.player.getBlockZ();
                }

                int tileSize = info.tileSize();
                int rangeT = (int) Math.ceil(info.hiresViewDistance() / tileSize) + 1;
                int totalCandidates = (2 * rangeT + 1) * (2 * rangeT + 1);
                if (cfg.maxTilesPerSync > 0) totalCandidates = Math.min(totalCandidates, cfg.maxTilesPerSync);
                final int totalTiles = totalCandidates;

                Map<ChunkPos, PrbmParser.ChunkData> explored =
                        apiClient.fetchExploredChunks(info, centerX, centerZ, done -> {
                    if (done % 150 == 0 || done == totalTiles) {
                        int percent = (int) ((done * 100.0) / Math.max(1, totalTiles));
                        sendMsg(source, "§6[BlueMap Sync] §rProgress: §e" + percent + "% §7(" + done + "/" + totalTiles + " tiles)");
                    }
                });

                if (apiClient.isCancelled()) {
                    sendMsg(source, "§e[BlueMap Sync] Sync was cancelled.");
                    return;
                }

                int tileCount = explored.size();
                long downloadTime = System.currentTimeMillis() - startTime;
                sendMsg(source, "§6[BlueMap Sync] §rDownloaded §a" + tileCount + " §rchunks in §e" +
                        String.format("%.1f", downloadTime / 1000.0) + "s§r. Revealing in Xaero's World Map…");

                if (tileCount == 0) {
                    sendMsg(source, "§e[BlueMap Sync] No rendered tiles found in range.");
                    return;
                }

                if (!XaeroMapBridge.isXaeroPresent()) {
                    sendMsg(source, "§e[BlueMap Sync] Xaero's World Map not detected. Saved to xaeros_bluemap_sync.txt");
                    return;
                }

                int revealed = XaeroMapBridge.revealChunks(explored, Minecraft.getInstance());

                long totalTime = System.currentTimeMillis() - startTime;
                if (revealed <= 0) {
                    sendMsg(source, "§e[BlueMap Sync] 0 chunks revealed in Xaero.");
                } else {
                    sendMsg(source, "§a[BlueMap Sync] Sync Complete! §r" + revealed + " chunks synced and saved in §a" +
                            String.format("%.1f", totalTime / 1000.0) + "s§r.");
                }

            } catch (Exception e) {
                if (apiClient.isCancelled()) {
                    sendMsg(source, "§e[BlueMap Sync] Sync was cancelled.");
                } else {
                    Xaeros_bluemap_addon.LOGGER.error("[XaerosBluemapAddon] Sync failed", e);
                    sendMsg(source, "§c[BlueMap Sync] Sync error: " + e.getMessage());
                }
            } finally {
                activeClient = null;
                apiClient.shutdown();
                syncRunning = false;
            }
        }, "bmsync-worker");

        syncThread.setDaemon(true);
        syncThread.start();
        sendMsg(source, "§6[BlueMap Sync] §rSync started in background. You will be notified when done.");
    }

    private static void sendMsg(FabricClientCommandSource source, String rawMessage) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> source.sendFeedback(Component.literal(rawMessage)));
    }
}
