package onarous.xaeros_bluemap_addon;

import net.fabricmc.api.ModInitializer;
import onarous.xaeros_bluemap_addon.config.BluemapSyncConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Xaeros_bluemap_addon implements ModInitializer {

    public static final String MOD_ID = "xaeros_bluemap_addon";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        // Load config on startup (creates default if absent)
        BluemapSyncConfig.getInstance();
        LOGGER.info("[XaerosBluemapAddon] Mod initialized. Config loaded from config/xaeros_bluemap_addon.json");
    }
}
