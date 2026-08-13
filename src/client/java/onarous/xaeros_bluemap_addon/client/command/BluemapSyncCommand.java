package onarous.xaeros_bluemap_addon.client.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;
import onarous.xaeros_bluemap_addon.Xaeros_bluemap_addon;
import onarous.xaeros_bluemap_addon.client.bluemap.BluemapApiClient;
import onarous.xaeros_bluemap_addon.client.xaero.XaeroMapBridge;
import onarous.xaeros_bluemap_addon.config.BluemapSyncConfig;

import onarous.xaeros_bluemap_addon.client.bluemap.PrbmParser;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/**
 * Registers the {@code /bmsync} client command.
 *
 * <pre>
 * /bmsync start           – fetch BlueMap tiles and reveal them in Xaero's World Map
 * /bmsync status          – print current config
 * /bmsync maps            – list available maps from the configured BlueMap server
 * /bmsync seturl &lt;url&gt;   – change the BlueMap server URL
 * /bmsync setmap &lt;mapId&gt; – change which BlueMap map to sync
 * /bmsync reload          – reload config from disk
 * </pre>
 */
public class BluemapSyncCommand {

    /** Whether a sync is currently running (prevents concurrent syncs). */
    private static volatile boolean syncRunning = false;

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(
            literal("bmsync")
                // ── /bmsync start ────────────────────────────────────────────
                .then(literal("start").executes(ctx -> {
                    if (syncRunning) {
                        sendMsg(ctx.getSource(), "§eA sync is already running, please wait…");
                        return 0;
                    }
                    startSync(ctx.getSource());
                    return 1;
                }))

                // ── /bmsync status ───────────────────────────────────────────
                .then(literal("status").executes(ctx -> {
                    BluemapSyncConfig cfg = BluemapSyncConfig.getInstance();
                    sendMsg(ctx.getSource(),
                            "§6[BlueMap Sync] §rCurrent config:\n" +
                            "  §aURL:  §f" + cfg.bluemapUrl + "\n" +
                            "  §aMap:  §f" + cfg.mapId + "\n" +
                            "  §aTile size: §f" + cfg.hiresBlockSize + " blocks\n" +
                            "  §aParallel requests: §f" + cfg.parallelRequests + "\n" +
                            "  §aMax tiles: §f" + (cfg.maxTilesPerSync == 0 ? "unlimited" : cfg.maxTilesPerSync) + "\n" +
                            "  §aXaero's World Map: §f" + (XaeroMapBridge.isXaeroPresent() ? "§aDetected" : "§cNot found"));
                    return 1;
                }))

                // ── /bmsync maps ─────────────────────────────────────────────
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

                // ── /bmsync seturl <url> ─────────────────────────────────────
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

                // ── /bmsync setmap <mapId> ───────────────────────────────────
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
                
                // ── /bmsync setrange <number> ────────────────────────────────
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

                // ── /bmsync reload ───────────────────────────────────────────
                .then(literal("reload").executes(ctx -> {
                    BluemapSyncConfig.load();
                    sendMsg(ctx.getSource(), "§6[BlueMap Sync] §aConfig reloaded from disk.");
                    return 1;
                }))
        );
    }

    // ── Sync logic ──────────────────────────────────────────────────────────

    private static void startSync(FabricClientCommandSource source) {
        BluemapSyncConfig cfg = BluemapSyncConfig.getInstance();

        Thread syncThread = new Thread(() -> {
            syncRunning = true;
            BluemapApiClient apiClient = new BluemapApiClient(cfg);
            try {
                sendMsg(source, "§6[BlueMap Sync] §rConnecting to §a" + cfg.bluemapUrl + "§r…");

                // 1. Fetch map info
                Optional<BluemapApiClient.MapInfo> infoOpt = apiClient.fetchMapInfo(cfg.mapId);
                if (infoOpt.isEmpty()) {
                    sendMsg(source, "§c[BlueMap Sync] Failed to fetch map info for '" + cfg.mapId + "'.\n" +
                            "  Check the URL and mapId with §e/bmsync status§r, or list maps with §e/bmsync maps§r.");
                    return;
                }
                BluemapApiClient.MapInfo info = infoOpt.get();
                sendMsg(source, "§6[BlueMap Sync] §rMap '§a" + info.name() + "§r' — hires view distance: §a" +
                        (int) info.hiresViewDistance() + " blocks§r. Probing tiles…");

                // 2. Probe tiles
                Map<ChunkPos, PrbmParser.ChunkData> explored = apiClient.fetchExploredChunks(info, done -> {
                    if (done % 200 == 0) {
                        sendMsg(source, "§6[BlueMap Sync] §r  Checked §a" + done + "§r tiles so far…");
                    }
                });

                int tileCount = explored.size();
                sendMsg(source, "§6[BlueMap Sync] §rFound §a" + tileCount + " §rexplored chunks on BlueMap.");

                if (tileCount == 0) {
                    sendMsg(source, "§e[BlueMap Sync] No rendered tiles found. Is the URL correct?");
                    return;
                }

                // 3. Reveal in Xaero
                if (!XaeroMapBridge.isXaeroPresent()) {
                    sendMsg(source, "§e[BlueMap Sync] Xaero's World Map not detected. " +
                            "Chunk positions saved to xaeros_bluemap_sync.txt in your .minecraft folder.");
                    return;
                }

                sendMsg(source, "§6[BlueMap Sync] §rRevealing chunks in Xaero's World Map…");
                int revealed = XaeroMapBridge.revealChunks(explored, Minecraft.getInstance());

                if (revealed < 0) {
                    sendMsg(source, "§c[BlueMap Sync] Xaero's World Map is not installed or not compatible.");
                } else if (revealed == 0) {
                    sendMsg(source, "§e[BlueMap Sync] Reflection reveal returned 0 chunks marked. " +
                            "Chunk list saved to §fxaeros_bluemap_sync.txt§e as fallback.");
                } else {
                    sendMsg(source, "§a[BlueMap Sync] Done! §r" + revealed + " chunks revealed in Xaero's World Map.");
                }

            } catch (Exception e) {
                Xaeros_bluemap_addon.LOGGER.error("[XaerosBluemapAddon] Sync failed", e);
                sendMsg(source, "§c[BlueMap Sync] Sync error: " + e.getMessage());
            } finally {
                apiClient.shutdown();
                syncRunning = false;
            }
        }, "bmsync-worker");

        syncThread.setDaemon(true);
        syncThread.start();
        sendMsg(source, "§6[BlueMap Sync] §rSync started in background. You will be notified when done.");
    }

    // ── Chat utility ─────────────────────────────────────────────────────────

    private static void sendMsg(FabricClientCommandSource source, String rawMessage) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> source.sendFeedback(Component.literal(rawMessage)));
    }
}
