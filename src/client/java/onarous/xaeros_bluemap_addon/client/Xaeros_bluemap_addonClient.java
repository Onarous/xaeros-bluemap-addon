package onarous.xaeros_bluemap_addon.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import onarous.xaeros_bluemap_addon.Xaeros_bluemap_addon;
import onarous.xaeros_bluemap_addon.client.command.BluemapSyncCommand;
import onarous.xaeros_bluemap_addon.client.xaero.XaeroMapBridge;

public class Xaeros_bluemap_addonClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // Register /bmsync and all its sub-commands
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                BluemapSyncCommand.register(dispatcher));

        if (XaeroMapBridge.isXaeroPresent()) {
            Xaeros_bluemap_addon.LOGGER.info("[XaerosBluemapAddon] Xaero's World Map detected – integration enabled.");
        } else {
            Xaeros_bluemap_addon.LOGGER.warn("[XaerosBluemapAddon] Xaero's World Map NOT detected. " +
                    "Chunk positions will be written to xaeros_bluemap_sync.txt instead.");
        }
    }
}
