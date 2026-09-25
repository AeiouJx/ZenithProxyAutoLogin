package dev.zenith.autologin.module;

import com.github.rfresh2.EventConsumer;
import com.zenith.Proxy;
import com.zenith.event.chat.PublicChatEvent;
import com.zenith.event.chat.SystemChatEvent;
import com.zenith.event.chat.WhisperChatEvent;
import com.zenith.event.client.ClientDisconnectEvent;
import com.zenith.event.client.ClientOnlineEvent;
import com.zenith.event.client.ClientStartConnectEvent;
import com.zenith.module.api.Module;
import com.zenith.network.client.ClientSession;
import com.zenith.network.codec.PacketHandlerCodec;
import com.zenith.network.codec.PacketHandlerStateCodec;
import com.zenith.util.ChatUtil;
import com.zenith.util.ComponentSerializer;
import org.geysermc.mcprotocollib.protocol.data.ProtocolState;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundPlayerChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundSystemChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatPacket;

import java.util.List;
import java.util.Locale;

import static com.github.rfresh2.EventConsumer.of;
import static com.zenith.Globals.CONFIG;
import static com.zenith.util.config.Config.Authentication.AccountType.OFFLINE;
import static dev.zenith.autologin.AutoLoginPlugin.PLUGIN_CONFIG;

/**
 * Answers server authentication prompts automatically.
 *
 * <p>Nothing is sent on join. The server has to ask first, so the bot never
 * guesses: whatever keyword the prompt contains decides between
 * {@code /register} and {@code /login}, which also means a proxy redirect to a
 * different host cannot desync the plugin from the server it is talking to.
 *
 * <p>Prompts are picked up on two layers, because either one alone has holes:
 * the chat events are skipped for action bar packets and depend on chat schema
 * parsing, while the raw packet layer sees every message. The attempt limiter
 * keeps the overlap from turning into duplicate commands.
 *
 * <p>Accepted logins are detected too. Without that the only evidence the
 * plugin worked is the absence of a kick, which is exactly the kind of silence
 * that hides a misconfigured trigger keyword.
 */
public class AutoLogin extends Module {

    private enum Action {
        REGISTER,
        LOGIN,
        SUCCESS
    }

    private final Object responseLock = new Object();
    private int attempts = 0;
    private boolean exhaustedLogged = false;
    private boolean missingPasswordLogged = false;
    private long lastResponseMillis = 0L;
    private String lastTriggerText = null;
    private long lastTriggerMillis = 0L;

    @Override
    public boolean enabledSetting() {
        return PLUGIN_CONFIG.enabled;
    }

    @Override
    public List<EventConsumer<?>> registerEvents() {
        return List.of(
            of(SystemChatEvent.class, event -> handlePromptText(event.message())),
            of(PublicChatEvent.class, event -> {
                if (PLUGIN_CONFIG.matchPlayerChat) handlePromptText(event.message());
            }),
            of(WhisperChatEvent.class, event -> {
                if (PLUGIN_CONFIG.matchPlayerChat) handlePromptText(event.message());
            }),
            of(ClientStartConnectEvent.class, event -> resetAttempts("startConnect")),
            of(ClientOnlineEvent.class, event -> resetAttempts("online")),
            of(ClientDisconnectEvent.class, event -> resetAttempts("disconnect"))
        );
    }

    /**
     * Fallback detection layer for prompts that never reach a chat event:
     * action bar messages, and system messages a custom chat schema
     * reclassifies as player chat.
     */
    @Override
    public PacketHandlerCodec registerServerPacketHandlerCodec() {
        return PacketHandlerCodec.serverBuilder()
            .setId("autologin")
            .setPriority(1000)
            .state(ProtocolState.GAME, PacketHandlerStateCodec.serverBuilder()
                .inbound(ClientboundSystemChatPacket.class, (packet, session) -> {
                    handlePromptText(ComponentSerializer.serializePlain(packet.getContent()));
                    return packet;
                })
                .inbound(ClientboundPlayerChatPacket.class, (packet, session) -> {
                    if (PLUGIN_CONFIG.matchPlayerChat) {
                        final var unsignedContent = packet.getUnsignedContent();
                        handlePromptText(unsignedContent != null
                            ? ComponentSerializer.serializePlain(unsignedContent)
                            : packet.getContent());
                    }
                    return packet;
                })
                .build())
            .build();
    }

    @Override
    public void onDisable() {
        resetAttempts("disable");
    }

    private void resetAttempts(final String reason) {
        synchronized (responseLock) {
            if (attempts != 0) debug("Reset attempt counter ({})", reason);
            attempts = 0;
            exhaustedLogged = false;
            missingPasswordLogged = false;
            lastResponseMillis = 0L;
            lastTriggerText = null;
            lastTriggerMillis = 0L;
        }
    }

    private void handlePromptText(final String text) {
        if (text == null) return;
        final String trimmed = text.trim();
        if (trimmed.isEmpty()) return;
        // a real player is driving, never talk over them
        if (Proxy.getInstance().hasActivePlayer()) return;

        final Action action = matchAction(trimmed);
        if (action == null) return;
        if (action == Action.SUCCESS) {
            onAuthenticated();
            return;
        }

        if (!isCurrentServerAllowed()) {
            debug("Prompt on out of scope server {}, ignoring. Whitelist: {}",
                CONFIG.client.server.address, PLUGIN_CONFIG.serverWhitelist);
            return;
        }

        final String username = CONFIG.authentication.username;
        final String password = resolvePassword(username);
        if (password == null || password.isBlank()) {
            // no attempt is consumed, so this can repeat on every matching
            // message - log it once per connection instead
            synchronized (responseLock) {
                if (missingPasswordLogged) return;
                missingPasswordLogged = true;
            }
            warn("Authentication prompt matched but no password is available for {}, "
                + "run `autoLogin password <value>` or set the account password", username);
            return;
        }

        final ClientSession client = Proxy.getInstance().getClient();
        if (client == null || !client.isConnected()) return;

        if (!tryAcquireAttempt(trimmed)) return;

        final String command = action == Action.REGISTER
            ? "/register " + password + " " + password
            : "/login " + password;
        info("Prompt matched on {}, sending /{} (attempt {}/{})",
            CONFIG.client.server.address, action == Action.REGISTER ? "register" : "login",
            attempts, PLUGIN_CONFIG.maxAttempts);
        // the slash is what lets ZenithProxy's own outgoing handler turn this
        // into a command packet, including chat signing decisions
        sendClientPacketAsync(new ServerboundChatPacket(ChatUtil.sanitizeChatMessage(command)));
    }

    /**
     * The server accepted our credentials. This is the only positive
     * confirmation the plugin can get, and it is what turns a silent failure
     * into a visible one. The attempt budget is handed back so a later
     * re-authentication on the same connection starts fresh.
     */
    private void onAuthenticated() {
        info("Authentication succeeded on {}", CONFIG.client.server.address);
        resetAttempts("authenticated");
    }

    private Action matchAction(final String text) {
        final String lowerCase = text.toLowerCase(Locale.ROOT);
        synchronized (PLUGIN_CONFIG) {
            // checked first: a loose login keyword would otherwise match the
            // success line itself and answer a login that already succeeded
            if (containsAny(lowerCase, PLUGIN_CONFIG.successTriggers)) return Action.SUCCESS;
            final boolean register = containsAny(lowerCase, PLUGIN_CONFIG.registerTriggers);
            final boolean login = containsAny(lowerCase, PLUGIN_CONFIG.loginTriggers);
            if (register && PLUGIN_CONFIG.autoRegister) return Action.REGISTER;
            if (login) return Action.LOGIN;
            return null;
        }
    }

    private static boolean containsAny(final String lowerCaseText, final List<String> keywords) {
        for (final String keyword : keywords) {
            if (keyword == null) continue;
            final String trimmed = keyword.trim().toLowerCase(Locale.ROOT);
            // an empty entry would otherwise match every single message
            if (!trimmed.isEmpty() && lowerCaseText.contains(trimmed)) return true;
        }
        return false;
    }

    private String resolvePassword(final String username) {
        synchronized (PLUGIN_CONFIG) {
            final var credential = PLUGIN_CONFIG.credentials.get(username.toLowerCase(Locale.ROOT));
            if (credential != null && !credential.password.isBlank()) return credential.password;
        }
        // fall back to the account password so the plugin works before
        // `autoLogin password` has ever been run
        return CONFIG.authentication.password;
    }

    private boolean tryAcquireAttempt(final String triggerText) {
        synchronized (responseLock) {
            if (attempts >= PLUGIN_CONFIG.maxAttempts) {
                if (!exhaustedLogged) {
                    exhaustedLogged = true;
                    warn("Reached {} response attempts without authenticating, "
                        + "giving up until the next connection", PLUGIN_CONFIG.maxAttempts);
                }
                return false;
            }
            final long now = System.currentTimeMillis();
            if (triggerText.equals(lastTriggerText) && now - lastTriggerMillis < PLUGIN_CONFIG.debounceMillis) {
                return false;
            }
            if (now - lastResponseMillis < PLUGIN_CONFIG.retryDelayMillis) return false;
            attempts++;
            lastTriggerText = triggerText;
            lastTriggerMillis = now;
            lastResponseMillis = now;
            return true;
        }
    }

    private boolean isCurrentServerAllowed() {
        if (PLUGIN_CONFIG.offlineAuthOnly && CONFIG.authentication.accountType != OFFLINE) return false;
        final String host = normalizeHost(CONFIG.client.server.address);
        if (host.isEmpty()) return false;
        synchronized (PLUGIN_CONFIG) {
            for (final String rule : PLUGIN_CONFIG.serverBlacklist) {
                if (matchesHost(host, rule)) return false;
            }
            if (PLUGIN_CONFIG.serverWhitelist.isEmpty()) return true;
            for (final String rule : PLUGIN_CONFIG.serverWhitelist) {
                if (matchesHost(host, rule)) return true;
            }
            return false;
        }
    }

    /**
     * Reduces an address to a bare comparable host, tolerating the shapes
     * ZenithProxy stores: {@code play.3c3u.org}, {@code play.3c3u.org:25565} and
     * the {@code host/1.2.3.4:25565} form a redirect logs.
     */
    public static String normalizeHost(final String address) {
        if (address == null) return "";
        String host = address.trim().toLowerCase(Locale.ROOT);
        final int slash = host.indexOf('/');
        if (slash != -1) host = host.substring(0, slash);
        // strip a trailing port, but leave ipv6 literals alone
        final int firstColon = host.indexOf(':');
        if (firstColon != -1 && firstColon == host.lastIndexOf(':')) host = host.substring(0, firstColon);
        return host;
    }

    /**
     * Host suffix match, so one rule covers a domain and every subdomain of it.
     * {@code 3c3u.org}, {@code *.3c3u.org} and {@code .3c3u.org} all accept
     * {@code play.3c3u.org}; the leading dot form excludes the apex.
     */
    public static boolean matchesHost(final String host, final String rule) {
        if (host == null || rule == null) return false;
        String suffix = normalizeHost(rule);
        if (suffix.isEmpty() || host.isEmpty()) return false;
        if (suffix.startsWith("*.")) suffix = suffix.substring(1);
        if (!suffix.startsWith(".")) suffix = "." + suffix;
        return host.equals(suffix.substring(1)) || host.endsWith(suffix);
    }
}
