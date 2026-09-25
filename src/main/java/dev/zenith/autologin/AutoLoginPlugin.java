package dev.zenith.autologin;

import com.zenith.plugin.api.Plugin;
import com.zenith.plugin.api.PluginAPI;
import com.zenith.plugin.api.ZenithProxyPlugin;
import dev.zenith.autologin.command.AutoLoginCommand;
import dev.zenith.autologin.module.AutoLogin;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

@Plugin(
    id = BuildConstants.PLUGIN_ID,
    version = BuildConstants.VERSION,
    description = "Automates /login and /register for ZenithProxy on offline-mode servers.",
    url = "https://github.com/AeiouJx/ZenithProxyAutoLogin",
    authors = {"AeiouJx"},
    mcVersions = {BuildConstants.MC_VERSION}
)
public class AutoLoginPlugin implements ZenithProxyPlugin {
    public static AutoLoginConfig PLUGIN_CONFIG;
    public static ComponentLogger LOG;

    @Override
    public void onLoad(PluginAPI pluginAPI) {
        LOG = pluginAPI.getLogger();
        LOG.info("AutoLogin Plugin loading...");
        // initialize the configuration before the module or command might read it
        PLUGIN_CONFIG = pluginAPI.registerConfig(BuildConstants.PLUGIN_ID, AutoLoginConfig.class);
        pluginAPI.registerModule(new AutoLogin());
        pluginAPI.registerCommand(new AutoLoginCommand());
        LOG.info("AutoLogin Plugin loaded!");
    }
}
