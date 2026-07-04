# Nexora-Heal

![Minecraft](https://img.shields.io/badge/Minecraft-26.1-3B9F3B?logo=minecraft&logoColor=white)
![Fabric](https://img.shields.io/badge/Loader-Fabric-3E7DDA)
![License](https://img.shields.io/badge/license-MIT-blue)

Client-side Fabric mod that auto-uses a healing item when your HP drops
below a set threshold — simulates real keyboard/mouse input, no packet
manipulation.

It works by pressing the same keys the game already listens for (a hotbar
number and right-click) rather than sending packets or editing inventory
state directly, so it behaves identically in singleplayer and multiplayer.

## Features

- **Auto-heal** — below a configurable HP%, switches to a set hotbar slot,
  simulates a right-click to use the item, then switches back to whatever
  slot you had selected.
- **Cooldown-aware** — won't try again until the item's ability cooldown
  (configurable) has actually passed.
- **HUD indicator** — corner overlay (position configurable) showing live
  HP%, heal-ready state, and a cooldown countdown bar.
- **Sound notification** — optional chime when a heal fires.
- **`/showhp`** — prints current/max HP to chat.
- **Settings screen** — `/nexora` in chat, or through
  [ModMenu](https://modrinth.com/mod/modmenu) if you have it installed.

## Requirements

- Minecraft 26.1
- Fabric Loader 0.19.0+
- Fabric API
- [ModMenu](https://modrinth.com/mod/modmenu) 18.0.0-beta.1+ and its
  dependency, Text Placeholder API — both **optional**, only needed for the
  mods-list config button. `/nexora` always works without them.

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/) for Minecraft 26.1.
2. Drop [Fabric API](https://modrinth.com/mod/fabric-api) and the built
   `nexora-heal-<version>.jar` into your `mods` folder.
3. Launch the game, then run `/nexora` to configure your heal item slot.

## Building from source

Minecraft 26.1 ships unobfuscated, so no Yarn/official-mappings step is
needed — Loom compiles directly against the game jar.

```
./gradlew build
```

The output jar will be at `build/libs/nexora-heal-<version>.jar`.

## Configuration

Settings live in `config/nexora-heal.properties` and are editable in-game via
`/nexora`:

| Setting | Description |
|---|---|
| Auto-Heal | Master on/off toggle |
| Heal Below % | HP percentage that triggers a heal attempt |
| Heal Item Slot | Hotbar slot (1-9) holding the heal item |
| Cooldown | Item's ability cooldown in seconds (mod waits cooldown + 0.5s before trying again) |
| Heal Sound | Toggle the notification sound on heal |
| HUD Position | Which screen corner the indicator is drawn in |

## How it works

Rather than calling internal game logic directly, the mod flips the same
`KeyMapping` state the game's own mouse/keyboard input handlers set on a
real press — `KeyMapping.click(...)` for the hotbar switch and
`KeyMapping.set(...)` for the right-click. The game's normal input
processing takes it from there: switching slots, starting the item use, and
sending whatever packets that naturally implies. The mod never touches
inventory contents, networking, or server state directly.

## Credits

Built with the help of [Claude Code](https://claude.com/claude-code). ( yes i still know how to code, but this project doesnt need safety / optimisation )

## License

MIT
