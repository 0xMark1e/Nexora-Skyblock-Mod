# Nexora

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

- **Auto-heal** — below a configurable HP%, auto-detects whichever hotbar
  slot holds your healing wand (any item whose internal ID starts with
  `WAND_OF_`), switches to it, simulates a right-click to use it, then
  switches back to whatever slot you had selected. No slot to configure —
  move the wand around your hotbar freely.
- **Cooldown-aware** — won't try again until the item's ability cooldown
  (configurable) has actually passed.
- **Ragnarock-aware** — won't interrupt a held Ragnarock axe to heal;
  waits until you switch away from it yourself (toggleable, on by default).
- **Panic heal** — below a lower, separately configurable HP% (25% by
  default), switches to your `FLORID_ZOMBIE_SWORD` and spams its
  right-click ability until the server reports you're out of charges,
  then waits out the reported recharge timer before trying again.
  Overrides the normal cooldown/Ragnarock logic since it's a last resort.
- **HUD indicator** — corner overlay (position configurable) showing live
  HP%, heal-ready state, a cooldown countdown bar, and (if enabled) a
  second row for panic-heal status.
- **Sound notification** — optional chime when a heal fires.
- **Blaze Slayer auto-attunement** — reads the Hellion Shield boss's
  attunement (Ashen/Auric/Spirit/Crystal) off its nametag and keeps your
  `HEARTFIRE_DAGGER`/`HEARTMAW_DAGGER` switched to whichever dagger and
  toggle state matches it, simulating the same right-click-to-toggle input
  you'd use by hand. Debounced against nametag flicker, backs off after
  repeated failed attempts instead of spamming, and won't fire mid-panic-heal
  or while holding Ragnarock (if avoidance is on).
- **`/testtext <message>`** — flashes the message as a big neon-pink
  announcement title in the upper middle of the screen.
- **`/testdrop <item name>`** — the same announcement with the item's
  live price in orange-gold, e.g. "Chimera (32.1m)" — bazaar insta-sell
  price when the item is bazaar-traded, AH lowest BIN otherwise.
- **`/showhp`** — prints current/max HP to chat.
- **`/getid`** — prints the internal Skyblock item ID of whatever you're
  currently holding, for figuring out an item's ID.
- **Auto deployable** — the moment any slayer boss starts spawning (the
  sidebar flips to "Slay the boss!"), places whatever orb or flare is in
  your hotbar: switches to it, looks down, right-clicks, then restores
  your view and slot. Matches every tier of both families (Radiant/Mana
  Flux/Overflux/Plasmaflux orbs, Warning/Alert/SOS flares), once per boss.
- **Auto Soulcry** — while fighting a Voidgloom Seraph with any of the
  three slayer katanas (Voidedge, Voidwalker, Vorpal), re-casts the
  katana's Soulcry ability as soon as it expires, keeping the +200
  Ferocity buff up for the whole fight. The ability leaves no readable
  state on the item, so it re-taps right-click once a second — attempts
  during the active window are rejected by the server at no cost. A HUD
  row shows when it's running (or when you're at the boss without a
  katana in hand).
- **Auto-cake** — automatically looks at and eats cakes gifted to you in
  the hub (the "CLICK TO EAT" prompt), turning the camera smoothly toward
  it and clicking, just like you would by hand.
- **Drop announcements** — when chat announces a CRAZY RARE DROP! (neon
  pink) or INSANE DROP! (red), flashes the animated title with the item's
  live bazaar/AH price in gold, e.g. "Necron's Handle (475.0m)".
- **Sea creature alert** — when a notable fishing creature spawns
  (detected by nametag, e.g. Puddle Jumper), flashes a red alert with a
  chime — once per creature — then helps you find it: a mid-screen
  locator with distance and turn arrows, a tracer line from the crosshair
  toward it, and a vanilla glowing outline on the mob itself. Tracks out
  to render distance until it dies or despawns. A per-creature checkbox
  list in the Fishing tab picks which creatures alert (Puddle Jumper,
  Thunder, Lord Jawbus, Ragnarok, and more).
- **Debug commands** — a toolkit for figuring out how a game mechanic is
  actually represented, all appending dumps to `config/nexora-debug/`:
  `/dumpitem` (held item's full components), `/watchitem` (10s per-tick
  held-item diff), `/dumpentities [radius]` (nearby entities with NBT),
  `/dumpinventory`, `/dumpequipment`, `/watchchat` (30s chat/action-bar
  log), `/dumpscoreboard`, `/dumptablist`, `/dumptarget` (crosshair
  entity/block), and `/dumpeffects`.
- **Settings screen** — `/nexora` in chat, or through
  [ModMenu](https://modrinth.com/mod/modmenu) if you have it installed.
  Sidebar tabs (**Healing**, **Slayer**, **Fishing**, **Display**, **Misc**), a
  live search box that filters settings across all tabs, tooltips on every
  setting, and a per-tab Defaults reset.

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
3. Launch the game, then run `/nexora` to tune the threshold, cooldown, and other settings.

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
| Cooldown | Item's ability cooldown in seconds (mod waits cooldown + 0.5s before trying again) |
| Avoid Ragnarock | Don't interrupt a held Ragnarock to heal (default on) |
| Panic Heal | Master on/off toggle for the panic-heal sword |
| Panic Below % | HP percentage that triggers a panic heal |
| Heal Sound | Toggle the notification sound on heal |
| Show HUD | Show or completely hide the HUD overlay |
| HUD Position | Which screen corner the indicator is drawn in |
| Auto Attunement | Master on/off toggle for Blaze Slayer dagger auto-switching |
| Swap Delay | How long to wait between toggle attempts (ms) |
| Show Attunement | Toggle the boss's current attunement in the HUD |
| Auto Deployable | Place your hotbar orb/flare when a slayer boss spawns |
| Auto Soulcry | Keep the katana's Soulcry buff active during Voidgloom fights |
| Auto Cake | Auto-collect cakes gifted to you (Misc tab) |
| Drop Announce | Flash the title with live price on CRAZY RARE/INSANE drops |
| Creature Alert | Red alert + glow when a notable fishing creature spawns (Fishing tab) |

## How it works

Rather than calling internal game logic directly, the mod flips the same
`KeyMapping` state the game's own mouse/keyboard input handlers set on a
real press — `KeyMapping.click(...)` for the hotbar switch and
`KeyMapping.set(...)` for the right-click. The game's normal input
processing takes it from there: switching slots, starting the item use, and
sending whatever packets that naturally implies. The mod never touches
inventory contents, networking, or server state directly.

The attunement switcher's decision logic (`AttunementController`) is kept
free of Minecraft types so it can be unit tested in isolation, without Loom
or a game jar on the classpath — see `src/test/java`, run with:

```
javac -d /tmp/out src/main/java/com/nexora/hp/AttunementController.java src/test/java/com/nexora/hp/AttunementControllerTest.java
java -cp /tmp/out com.nexora.hp.AttunementControllerTest
```

## Credits

Built with the help of [Claude Code](https://claude.com/claude-code). ( yes i still know how to code, but this project doesnt need safety / optimisation )

## License

MIT
