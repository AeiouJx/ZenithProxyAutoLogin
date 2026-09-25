package dev.zenith.autologin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration POJO.
 *
 * Saved and loaded as JSON at {@code plugins/config/autoLogin.json}.
 * Save and load is handled automatically by ZenithProxy, so this file can be
 * edited while the proxy is stopped without any risk of it being overwritten
 * by stale in-memory values.
 *
 * All fields must stay public and mutable, and static inner classes are
 * serialized as nested JSON objects.
 *
 * Collections are read from network threads while commands mutate them, so all
 * access must happen while holding this config instance as a monitor.
 */
public class AutoLoginConfig {

    /**
     * Master switch, toggled with {@code autoLogin on/off}.
     */
    public boolean enabled = true;

    /**
     * Whether a matched register trigger may send {@code /register}.
     * When false, register prompts are ignored and only login triggers respond.
     */
    public boolean autoRegister = true;

    /**
     * Only respond while the account is authenticating offline.
     * Keeps the plugin inert on online-mode accounts, where a stray
     * {@code /login} would do nothing but pollute chat.
     */
    public boolean offlineAuthOnly = true;

    /**
     * Also match prompts delivered as player chat instead of system chat.
     * Some server plugins broadcast the prompt through the chat channel, and
     * servers with a custom chat schema can reclassify system messages.
     */
    public boolean matchPlayerChat = true;

    /**
     * Servers to respond on. Empty means every server.
     * Entries are host suffixes, so {@code 3c3u.org} covers
     * {@code play.3c3u.org}, {@code host2.3c3u.org} and any port.
     */
    public List<String> serverWhitelist = new ArrayList<>();

    /**
     * Servers to never respond on. Checked before the whitelist.
     */
    public List<String> serverBlacklist = new ArrayList<>();

    /**
     * Case-insensitive substrings that identify a login prompt.
     * Keep these command-shaped: a bare word like {@code password} also matches
     * ordinary player chat and can provoke a bogus {@code /login}.
     */
    public List<String> loginTriggers = defaultLoginTriggers();

    /**
     * Case-insensitive substrings that identify a register prompt.
     */
    public List<String> registerTriggers = defaultRegisterTriggers();

    /**
     * Responses allowed per connection before the plugin gives up, so a server
     * that keeps nagging cannot spam commands into a kick.
     */
    public int maxAttempts = 3;

    /**
     * Milliseconds to ignore triggers that arrive right after a response.
     * The same prompt is seen twice, once per detection layer, and some
     * servers repeat themselves.
     */
    public long debounceMillis = 1_500L;

    /**
     * Milliseconds that must pass before another attempt is allowed.
     * Longer than {@link #debounceMillis} so a genuine retry is not swallowed.
     */
    public long retryDelayMillis = 5_000L;

    /**
     * Passwords per username, so one config can drive an account rotation.
     * Falls back to {@code CONFIG.authentication.password} when the current
     * username has no entry here.
     */
    public Map<String, Credential> credentials = new LinkedHashMap<>();

    public static class Credential {
        public String password = "";
    }

    public static List<String> defaultLoginTriggers() {
        return new ArrayList<>(List.of("/login"));
    }

    public static List<String> defaultRegisterTriggers() {
        return new ArrayList<>(List.of("/register"));
    }
}
