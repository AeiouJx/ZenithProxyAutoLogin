# ZenithProxyAutoLogin

[![Build](https://github.com/AeiouJx/ZenithProxyAutoLogin/actions/workflows/build.yml/badge.svg)](https://github.com/AeiouJx/ZenithProxyAutoLogin/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/AeiouJx/ZenithProxyAutoLogin)](https://github.com/AeiouJx/ZenithProxyAutoLogin/releases)
[![License](https://img.shields.io/github/license/AeiouJx/ZenithProxyAutoLogin)](LICENSE)

A ZenithProxy plugin that automates `/login` and `/register` on
offline-mode Minecraft servers.

This is the post-connection companion to
[ZenithProxyOfflineUUID](https://github.com/AeiouJx/ZenithProxyOfflineUUID).
While `OfflineUUID` controls how the server identifies your bot,
`AutoLogin` handles what happens after the connection is accepted —
automatically responding to the server's authentication prompts so the
bot can run unattended.

## Features

- Automatically sends `/login <password>` when the server asks to log in
- Automatically sends `/register <password> <password>` when the server asks to register
- Detects an accepted login, so a working configuration is visible in the log instead of inferred from a missing kick
- Configurable trigger keywords, matched per message, so it works with any auth plugin
- Two detection layers, so prompts are caught even when the server uses an action bar or a custom chat schema
- Per-username credential storage, for account rotations
- Optional domain scoping that survives subdomain and port changes
- Attempt cap, debounce and retry delay, so a nagging server cannot spam the bot into a kick
- Works with ZenithProxy's AutoReconnect module
- Lightweight config-based approach

## Compatibility

- ZenithProxy `1.21.4`
- Java 21+

## Installation

### Option 1: Download a release

1. Open the [Releases](https://github.com/AeiouJx/ZenithProxyAutoLogin/releases) page.
2. Download the latest `ZenithProxyAutoLogin-<mc version>-<version>.jar`.
3. Put the jar in the `plugins` folder next to your ZenithProxy launcher.
4. Restart ZenithProxy. Loading plugins after launch is not supported.

### Option 2: Build from source

```powershell
.\gradlew.bat build
```

The built jar will be placed in `build/libs`.

## Quick Start

```text
autoLogin on
autoLogin password mySecret123
```

That is the whole setup. The plugin now sits silent until a server sends an
authentication prompt, then answers it with the stored password.

## Commands

Base command:

```text
autoLogin
```

| Command | Description |
|---|---|
| `autoLogin` | show current configuration and status |
| `autoLogin on/off` | enable or disable the plugin |
| `autoLogin register on/off` | allow or block `/register` responses |
| `autoLogin password <value>` | store the password for the current account |
| `autoLogin password clear` | remove the stored password for the current account |
| `autoLogin trigger add <keyword>` | add a login trigger keyword |
| `autoLogin trigger register add <keyword>` | add a register trigger keyword |
| `autoLogin trigger remove <keyword>` | remove a keyword from any of the three lists |
| `autoLogin trigger list` | list all trigger keywords |
| `autoLogin trigger register list` | list register trigger keywords |
| `autoLogin trigger reset` | restore the default keywords |
| `autoLogin trigger clear` | remove every keyword |
| `autoLogin success add <keyword>` | add a keyword that identifies an accepted login |
| `autoLogin success remove <keyword>` | remove a success keyword |
| `autoLogin success list` | list success keywords |
| `autoLogin success reset` | restore the default success keywords |
| `autoLogin clear` | remove every stored credential |
| `autoLogin clear <username>` | remove one stored credential |

The base command is also available as `autologin`.

Passwords are never echoed back. Command output only reports whether a
password is stored.

## How It Works

The plugin never sends anything on join. The server has to ask first, so
there is no guessing about whether an account is registered, and a proxy
redirect to a different host cannot desync the plugin from the server it is
actually talking to.

When a message arrives, the plugin checks it against the trigger keywords:

- a **success** keyword matches, log the accepted login and hand the attempt budget back
- a **register** keyword matches, and auto register is on, send
  `/register <password> <password>`
- a **login** keyword matches, send `/login <password>`

Success is checked first, so a message that reports an accepted login is
never answered. That closes a real trap: on a server prompting
`使用指令登录：/login <password>` and confirming with `成功登录！`, a login
keyword of `登录` would otherwise match the confirmation too and answer a
login that already succeeded.

Detection of an accepted login is worth having on its own. Without it, the
only evidence that autologin worked is the absence of a kick, which is
exactly the kind of silence that hides a misconfigured keyword. On success
the plugin logs

```text
[AutoLogin] Authentication succeeded on play.3c3u.org
```

and resets `attempts`, so a server that authenticates a second time on the
same connection still gets the full retry budget.

Messages are picked up on two layers, because either one alone has holes:

| Layer | Catches | Misses |
|---|---|---|
| Chat events (`SystemChatEvent`, `PublicChatEvent`, `WhisperChatEvent`) | parsed chat, easy to read | action bar packets, and messages a custom chat schema reclassifies |
| Raw packet handler (`ClientboundSystemChatPacket`, `ClientboundPlayerChatPacket`) | every message, schema independent | messages that are never sent as chat at all |

The attempt limiter is what keeps that overlap from turning into duplicate
commands: a repeated identical message is dropped inside `debounceMillis`,
any two responses are kept `retryDelayMillis` apart, and the plugin stops
after `maxAttempts` until the next connection.

## Why a domain change used to break autologin

The common failure mode for a naive implementation is a hardcoded server
check:

```java
// breaks the moment the server switches domain
"3c3u.org".equalsIgnoreCase(CONFIG.client.server.address)
```

If the server moves to `play.3c3u.org`, that comparison is false, the
handler returns before it ever looks at the message, and autologin dies
without a single error in the log.

This plugin has no hardcoded server. It responds on every server by default,
and when you do want scoping, entries are **host suffixes** matched against
`CONFIG.client.server.address` with the port and any `host/ip:port` form
stripped first. So one rule covers a domain and every subdomain of it:

| Rule | Matches |
|---|---|
| *(empty whitelist)* | every server |
| `3c3u.org` | `3c3u.org`, `play.3c3u.org`, `host2.3c3u.org`, `play.3c3u.org:25565` |
| `*.3c3u.org` | same as above |
| `.3c3u.org` | subdomains only, not the apex `3c3u.org` |

Matching is on dot boundaries, so `3c3u.org` does not match `evil-3c3u.org`.

To restrict the plugin to one domain, edit `plugins/config/autologin.json`
while the proxy is stopped:

```json
"serverWhitelist": ["*.3c3u.org"]
```

If a prompt matches but the server is out of scope, the reason is logged at
debug level together with the current address and whitelist, so this failure
mode is diagnosable instead of silent.

## Choosing Trigger Keywords

Defaults are `/login`, `/register` and `成功登录`, all of them specific.
That is deliberate: a bare word also matches text the server was never
asking anything with.

If your server phrases its prompt without the command, add a phrase that is
long enough to be unique to the prompt:

```text
autoLogin trigger add 使用指令登录
autoLogin trigger add Please login using
autoLogin trigger register add 首次进入服务器
autoLogin success add 绑定成功
```

Keywords are case-insensitive substring matches, so `/LOGIN` and `/login`
behave the same, and multi-word keywords are allowed.

**Prefer a longer phrase over a bare word.** A login keyword of `密码` looks
harmless, but 3c3u prints this to every freshly logged in player:

```text
您尚未绑定邮箱，邮箱是您找回密码的唯一方式.
```

That contains `密码`, so the bot would answer it with `/login` and spend an
attempt on a message that was never a prompt. Nothing below it needs a
keyword, since `使用指令登录：/login <password>` already contains `/login`.

Success keywords are matched first and suppress the response, so a keyword
that is slightly too loose is caught on the success line — but not on the
other post-login messages above, so keep them command-shaped or phrase-shaped
rather than single common words.

## Post-login messages

A successful login is usually followed by more server text. On 3c3u that is
the email reminder, the welcome line and the queue-skip help:

```text
成功登录！
您尚未绑定邮箱，邮箱是您找回密码的唯一方式.
绑定邮箱：/email add <邮箱> <确认邮箱>
欢迎加入 3C3U 服务器
潜行离开队列可执行以下操作保护账户安全：
更改密码：/changepassword
添加二步验证：/2fa add
```

The plugin stays silent on all of it: none of those lines contain a login or
register keyword. That is intentional, because each of those commands has to
be run once by a human and cannot be answered blindly:

| Line | Why the plugin does not answer it |
|---|---|
| `绑定邮箱：/email add` | needs a real mailbox you own, and only makes sense until the address is bound |
| `更改密码：/changepassword` | rewrites the account password; a wrong argument mid-flight can lock the account out |
| `添加二步验证：/2fa add` | starts a TOTP enrolment that needs a code from an authenticator app |

The first line of the block, `成功登录！`, is what the success keyword picks
up. Run those three commands yourself once and the server stops asking.

## Configuration

`plugins/config/autologin.json`, created on first launch:

ZenithProxy writes plugin configs when the proxy shuts down, so a brand new
install has no file on disk yet and runs entirely on the defaults below. Stop
the proxy once to materialise it, then edit it while the proxy is stopped.
Running `autoLogin` shows the live values.

| Field | Default | Description |
|---|---|---|
| `enabled` | `true` | plugin on/off |
| `autoRegister` | `true` | whether register triggers may send `/register` |
| `offlineAuthOnly` | `true` | only respond while the account is authenticating offline |
| `matchPlayerChat` | `true` | also match prompts delivered as player chat |
| `serverWhitelist` | `[]` | servers to respond on, empty means all |
| `serverBlacklist` | `[]` | servers to never respond on, checked first |
| `loginTriggers` | `["/login"]` | login prompt keywords |
| `registerTriggers` | `["/register"]` | register prompt keywords |
| `successTriggers` | `["成功登录", "successfully logged in", "logged in successfully"]` | keywords that identify an accepted login, matched first and never answered |
| `maxAttempts` | `3` | responses per connection before giving up |
| `debounceMillis` | `1500` | window in which a repeated identical prompt is ignored |
| `retryDelayMillis` | `5000` | minimum gap between two responses |
| `credentials` | `{}` | stored passwords, keyed by lowercase username |

## Passwords

`autoLogin password <value>` stores a password for the **current** account,
so a single config can drive an account rotation:

```text
autoLogin password secret-one   # while logged in as account one
autoLogin password secret-two   # while logged in as account two
```

If the current username has no stored credential, the plugin falls back to
the account password from ZenithProxy's own config, so it works before
`autoLogin password` has ever been run.

## Project Info

- Plugin name: `ZenithProxyAutoLogin`
- Plugin id: `autologin`
- Package: `dev.zenith.autologin`
- Repository name: `ZenithProxyAutoLogin`

## Versioning

Plugin versions are manual.

To release a new version, update `plugin_version` in [gradle.properties](gradle.properties), then build and tag the release.

## Release Workflow

This repository includes GitHub Actions for automation:

- every push and pull request runs the build workflow
- the publish workflow can be triggered manually to build and attach the jar to a GitHub Release

## License

This repository is licensed under the [LICENSE](LICENSE) file included in the project.
