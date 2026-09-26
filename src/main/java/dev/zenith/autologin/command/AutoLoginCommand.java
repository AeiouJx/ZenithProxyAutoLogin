package dev.zenith.autologin.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.zenith.command.api.Command;
import com.zenith.command.api.CommandCategory;
import com.zenith.command.api.CommandContext;
import com.zenith.command.api.CommandUsage;
import com.zenith.discord.Embed;
import dev.zenith.autologin.AutoLoginConfig;
import dev.zenith.autologin.module.AutoLogin;

import java.util.List;
import java.util.Locale;

import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.greedyString;
import static com.mojang.brigadier.arguments.StringArgumentType.string;
import static com.zenith.Globals.CONFIG;
import static com.zenith.Globals.MODULE;
import static com.zenith.command.brigadier.ToggleArgumentType.getToggle;
import static com.zenith.command.brigadier.ToggleArgumentType.toggle;
import static com.zenith.util.config.Config.Authentication.AccountType.OFFLINE;
import static dev.zenith.autologin.AutoLoginPlugin.PLUGIN_CONFIG;

public class AutoLoginCommand extends Command {

    @Override
    public CommandUsage commandUsage() {
        return CommandUsage.builder()
            .name("autoLogin")
            .aliases("autologin")
            .category(CommandCategory.MODULE)
            .description("""
                Automates /login and /register on offline-mode servers.

                The server has to send a prompt first, so the matching keyword
                decides whether /register or /login is sent.
                """)
            .usageLines(
                "on/off",
                "register on/off",
                "password <value>",
                "password clear",
                "trigger add <keyword>",
                "trigger register add <keyword>",
                "trigger remove <keyword>",
                "trigger list",
                "trigger reset",
                "success add <keyword>",
                "success remove <keyword>",
                "success list",
                "success reset",
                "clear",
                "clear <username>"
            )
            .build();
    }

    @Override
    public LiteralArgumentBuilder<CommandContext> register() {
        return command("autoLogin")
            .then(argument("toggle", toggle()).executes(c -> {
                PLUGIN_CONFIG.enabled = getToggle(c, "toggle");
                // sync so the module is actually toggled
                MODULE.get(AutoLogin.class).syncEnabledFromConfig();
                c.getSource().getEmbed()
                    .title("AutoLogin " + toggleStrCaps(PLUGIN_CONFIG.enabled));
            }))
            .then(literal("register").then(argument("toggle", toggle()).executes(c -> {
                PLUGIN_CONFIG.autoRegister = getToggle(c, "toggle");
                c.getSource().getEmbed()
                    .title("Auto Register " + toggleStrCaps(PLUGIN_CONFIG.autoRegister))
                    .description(PLUGIN_CONFIG.autoRegister
                        ? "Register prompts will send `/register <password> <password>`."
                        : "Register prompts are ignored, only login triggers respond.");
            })))
            .then(literal("password")
                .then(literal("clear").executes(c -> {
                    final String username = currentUsername();
                    synchronized (PLUGIN_CONFIG) {
                        if (PLUGIN_CONFIG.credentials.remove(username.toLowerCase(Locale.ROOT)) == null) {
                            c.getSource().getEmbed()
                                .title("No Stored Password")
                                .description("Nothing stored for `" + username + "`.");
                            return ERROR;
                        }
                    }
                    c.getSource().getEmbed()
                        .title("Password Cleared")
                        .description("Stored password removed for `" + username + "`.");
                    return OK;
                }))
                .then(argument("value", greedyString()).executes(c -> {
                    final String username = currentUsername();
                    final String value = getString(c, "value").trim();
                    if (value.isEmpty()) {
                        c.getSource().getEmbed()
                            .title("Invalid Password")
                            .description("Password cannot be blank.");
                        return ERROR;
                    }
                    synchronized (PLUGIN_CONFIG) {
                        PLUGIN_CONFIG.credentials
                            .computeIfAbsent(username.toLowerCase(Locale.ROOT), key -> new AutoLoginConfig.Credential())
                            .password = value;
                    }
                    c.getSource().getEmbed()
                        .title("Password Set")
                        .description("Stored for `" + username + "`, hidden from command output.");
                    return OK;
                })))
            .then(literal("trigger")
                .then(literal("list").executes(c -> {
                    c.getSource().getEmbed()
                        .title("Triggers")
                        .description(triggerSummary());
                }))
                .then(literal("reset").executes(c -> {
                    synchronized (PLUGIN_CONFIG) {
                        PLUGIN_CONFIG.loginTriggers = AutoLoginConfig.defaultLoginTriggers();
                        PLUGIN_CONFIG.registerTriggers = AutoLoginConfig.defaultRegisterTriggers();
                        PLUGIN_CONFIG.successTriggers = AutoLoginConfig.defaultSuccessTriggers();
                    }
                    c.getSource().getEmbed()
                        .title("Triggers Reset")
                        .description(triggerSummary());
                }))
                .then(literal("register")
                    .then(literal("list").executes(c -> {
                        c.getSource().getEmbed()
                            .title("Register Triggers")
                            .description("`" + String.join("` `", normalized(PLUGIN_CONFIG.registerTriggers)) + "`");
                    }))
                    .then(literal("add").then(argument("keyword", greedyString()).executes(c -> {
                        return addTrigger(c, PLUGIN_CONFIG.registerTriggers, "Register");
                    })))
                    .then(literal("remove").then(argument("keyword", greedyString()).executes(c -> {
                        return removeTrigger(c, List.of(PLUGIN_CONFIG.registerTriggers), "Register");
                    }))))
                .then(literal("add").then(argument("keyword", greedyString()).executes(c -> {
                    return addTrigger(c, PLUGIN_CONFIG.loginTriggers, "Login");
                })))
                .then(literal("remove").then(argument("keyword", greedyString()).executes(c -> {
                    return removeTrigger(c, List.of(
                        PLUGIN_CONFIG.loginTriggers,
                        PLUGIN_CONFIG.registerTriggers,
                        PLUGIN_CONFIG.successTriggers
                    ), "Trigger");
                })))
                .then(literal("clear").executes(c -> {
                    synchronized (PLUGIN_CONFIG) {
                        PLUGIN_CONFIG.loginTriggers.clear();
                        PLUGIN_CONFIG.registerTriggers.clear();
                        PLUGIN_CONFIG.successTriggers.clear();
                    }
                    c.getSource().getEmbed()
                        .title("Triggers Cleared")
                        .description("No trigger keywords left, AutoLogin will stay silent.");
                })))
            .then(literal("success")
                .then(literal("list").executes(c -> {
                    c.getSource().getEmbed()
                        .title("Success Triggers")
                        .description("`" + String.join("` `", normalized(PLUGIN_CONFIG.successTriggers)) + "`"
                            + "\nA message matching one of these is never treated as a prompt.");
                }))
                .then(literal("reset").executes(c -> {
                    synchronized (PLUGIN_CONFIG) {
                        PLUGIN_CONFIG.successTriggers = AutoLoginConfig.defaultSuccessTriggers();
                    }
                    c.getSource().getEmbed()
                        .title("Success Triggers Reset")
                        .description("`" + String.join("` `", normalized(PLUGIN_CONFIG.successTriggers)) + "`");
                }))
                .then(literal("add").then(argument("keyword", greedyString()).executes(c -> {
                    return addTrigger(c, PLUGIN_CONFIG.successTriggers, "Success");
                })))
                .then(literal("remove").then(argument("keyword", greedyString()).executes(c -> {
                    return removeTrigger(c, List.of(PLUGIN_CONFIG.successTriggers), "Success");
                }))))
            .then(literal("clear")
                .then(argument("username", string()).executes(c -> {
                    final String username = getString(c, "username").trim();
                    synchronized (PLUGIN_CONFIG) {
                        if (PLUGIN_CONFIG.credentials.remove(username.toLowerCase(Locale.ROOT)) == null) {
                            c.getSource().getEmbed()
                                .title("No Stored Password")
                                .description("Nothing stored for `" + username + "`.");
                            return ERROR;
                        }
                    }
                    c.getSource().getEmbed()
                        .title("Password Cleared")
                        .description("Stored password removed for `" + username + "`.");
                    return OK;
                }))
                .executes(c -> {
                    final int cleared;
                    synchronized (PLUGIN_CONFIG) {
                        cleared = PLUGIN_CONFIG.credentials.size();
                        PLUGIN_CONFIG.credentials.clear();
                    }
                    c.getSource().getEmbed()
                        .title("Credentials Cleared")
                        .description(cleared + " stored password(s) removed. "
                            + "Falls back to the account password.");
                    return OK;
                }));
    }

    private int addTrigger(final com.mojang.brigadier.context.CommandContext<CommandContext> c,
                           final List<String> triggers, final String label) {
        final String keyword = getString(c, "keyword").trim();
        if (keyword.isEmpty()) {
            c.getSource().getEmbed()
                .title("Invalid Keyword")
                .description("Keyword cannot be blank.");
            return ERROR;
        }
        final boolean added;
        synchronized (PLUGIN_CONFIG) {
            added = normalized(triggers).stream().noneMatch(keyword::equalsIgnoreCase);
            if (added) triggers.add(keyword);
        }
        c.getSource().getEmbed()
            .title(label + (added ? " Trigger Added" : " Trigger Already Exists"))
            .description("`" + keyword + "`");
        return added ? OK : ERROR;
    }

    private int removeTrigger(final com.mojang.brigadier.context.CommandContext<CommandContext> c,
                             final List<List<String>> triggerLists, final String label) {
        final String keyword = getString(c, "keyword").trim();
        boolean removed = false;
        synchronized (PLUGIN_CONFIG) {
            // a keyword can end up in both lists, so scan all of them instead
            // of short circuiting on the first hit
            for (final List<String> triggers : triggerLists) {
                removed |= triggers.removeIf(keyword::equalsIgnoreCase);
            }
        }
        if (!removed) {
            c.getSource().getEmbed()
                .title("Trigger Not Found")
                .description("`" + keyword + "` is not a configured trigger.");
            return ERROR;
        }
        c.getSource().getEmbed()
            .title(label + " Trigger Removed")
            .description("`" + keyword + "`");
        return OK;
    }

    private static List<String> normalized(final List<String> keywords) {
        return keywords.stream().filter(keyword -> keyword != null && !keyword.isBlank()).toList();
    }

    private static String triggerSummary() {
        final List<String> login;
        final List<String> register;
        final List<String> success;
        synchronized (PLUGIN_CONFIG) {
            login = normalized(PLUGIN_CONFIG.loginTriggers);
            register = normalized(PLUGIN_CONFIG.registerTriggers);
            success = normalized(PLUGIN_CONFIG.successTriggers);
        }
        return "**Login**\n" + formatKeywords(login)
            + "\n**Register**\n" + formatKeywords(register)
            + "\n**Success**\n" + formatKeywords(success)
            + "\nCase-insensitive substring match against server messages.";
    }

    private static String formatKeywords(final List<String> keywords) {
        return keywords.isEmpty() ? "_none_" : keywords.stream().map(k -> "`" + k + "`").reduce((a, b) -> a + " " + b).orElse("_none_");
    }

    /**
     * {@code offlineAuthOnly} silently gates every response, so a mismatched
     * account type has to be visible here instead of only in the debug log.
     */
    private static String accountTypeSummary() {
        final boolean offlineOnly;
        synchronized (PLUGIN_CONFIG) {
            offlineOnly = PLUGIN_CONFIG.offlineAuthOnly;
        }
        final String type = String.valueOf(CONFIG.authentication.accountType);
        if (!offlineOnly) return type + " (any allowed)";
        return CONFIG.authentication.accountType == OFFLINE
            ? type
            : type + " — blocked, offlineAuthOnly is on";
    }

    /**
     * The blacklist takes part in matching but was missing from the status
     * embed, so a scoped setup could not be confirmed from the panel.
     */
    private static String scopeSummary() {
        final List<String> whitelist;
        final List<String> blacklist;
        synchronized (PLUGIN_CONFIG) {
            whitelist = PLUGIN_CONFIG.serverWhitelist.stream()
                .filter(rule -> rule != null && !rule.isBlank()).toList();
            blacklist = PLUGIN_CONFIG.serverBlacklist.stream()
                .filter(rule -> rule != null && !rule.isBlank()).toList();
        }
        final String allow = whitelist.isEmpty() ? "all servers" : String.join(", ", whitelist);
        return blacklist.isEmpty() ? allow : allow + "\nminus " + String.join(", ", blacklist);
    }

    private static String currentUsername() {
        return CONFIG.authentication.username;
    }

    @Override
    public void defaultEmbed(final Embed embed) {
        final String username = currentUsername();
        final boolean storedPassword;
        final int loginCount;
        final int registerCount;
        final int successCount;
        synchronized (PLUGIN_CONFIG) {
            storedPassword = PLUGIN_CONFIG.credentials.containsKey(username.toLowerCase(Locale.ROOT));
            loginCount = normalized(PLUGIN_CONFIG.loginTriggers).size();
            registerCount = normalized(PLUGIN_CONFIG.registerTriggers).size();
            successCount = normalized(PLUGIN_CONFIG.successTriggers).size();
        }
        embed
            .primaryColor()
            .addField("Enabled", toggleStr(PLUGIN_CONFIG.enabled))
            .addField("Auto Register", toggleStr(PLUGIN_CONFIG.autoRegister))
            .addField("Account", username)
            // never print the password itself, this embed has no permission check
            .addField("Password", storedPassword ? "stored (hidden)"
                : CONFIG.authentication.password.isBlank() ? "not set" : "account password")
            .addField("Server", CONFIG.client.server.address)
            .addField("Account Type", accountTypeSummary())
            .addField("Scope", scopeSummary())
            .addField("Triggers", loginCount + " login / " + registerCount + " register"
                + " / " + successCount + " success");
    }
}

