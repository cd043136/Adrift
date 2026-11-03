# Adrift

Rift & vampire slayer features for Hypixel Skyblock. Modrinth link: https://modrinth.com/mod/adrift-sb

Standalone Fabric mod: it only needs Fabric API and Fabric Language Kotlin. Open the settings with `/adrift`
(or bind **Open Config** in Controls), and move HUD elements with `/adrift hud`. The settings open as a window by
default; switch to a ClickGUI under **General → Menu → Style**.

| Minecraft | Fabric API | Fabric Language Kotlin |
|-----------|------------|------------------------|
| 26.1      | 0.151.0+   | 1.13.12+               |
| 26.2      | 0.161.0+   | 1.14.2+                |
| 26.3      | 0.165.0+   | 1.14.2+                |

## Features

### Boss

<details>
<summary>Boss Highlight</summary>

- Dynamic highlight, including a custom boss render
- Support for people's boss

![](https://cdn.modrinth.com/data/KMvVDXMP/images/ee27ae0ead5a62f599279ad007ed6ab23f8a03eb.png)

</details>

<details>
<summary>Ichor Helper</summary>

- Draws an indicator from boss to ichor so you don't miss it
- Support for other people's boss

![](https://cdn.modrinth.com/data/KMvVDXMP/images/e37d2bd5825b46dd285f70a093f66b66fd892ccc.png)
</details>

<details>
<summary>Twinclaws</summary>

- Louder Twinclaws countdown sound
- On-screen alert when Twinclaws starts, with an optional delay
</details>

<details>
<summary>Mania Helper</summary>

- Renders your mania pattern fully, fixes a bug where some blocks do not change colours during mania
- Also prevents you from dying in mania if someone's griefing your boss
- Status HUD: safe / move in / move out and time until coal

![](https://cdn.modrinth.com/data/KMvVDXMP/images/dd7ad4147b1bc62698ae68d843fa93b7d0584710.png)
</details>

<details>
<summary>Low Health Warning</summary>

- Alerts you when your health is critically low
</details>

<details>
<summary>Boss HUD</summary>

- Shows your boss's timer and nametag on screen
</details>

### Party

<details>
<summary>Party Announce</summary>

Sends to party chat, useful for lootsharing:
- Your boss's coordinates when it spawns
- Each mania phase you pick
- How long the boss took to kill
</details>

<details>
<summary>Beacon Soul Helper</summary>

- Sends the 2 player beacon soul grid to party chat
</details>

### Tracking

<details>
<summary>Splits</summary>

- Boss phase timer (damage, mania)
- Track & reset PB
</details>

<details>
<summary>Slayer Drops</summary>

- Rename bundle drops to "The One IV Bundle"
- Copy rare drops
</details>

## TODO
- Add images to README
- Clotgoyles
  - Custom rendering
  - Ground skull alert/indicator
  - Nearby indicator
- Damage counter?

## Development

Every supported Minecraft version is built from the same `src/` with [Stonecutter](https://stonecutter.kikugie.dev/).
Version-specific code is wrapped in comments such as `//? if >=26.2 {`, and per-version dependencies
(Fabric API, Fabric Language Kotlin, ...) live in `stonecutter.properties.toml`.

- `./gradlew buildAll` builds every version into `build/libs/`; `./gradlew :26.2:build` builds one.
- `./gradlew "Set active project to 26.1"` switches `src/` (and the IDE classpath) to another version.
- Run `./gradlew "Reset active project"` before committing, so `src/` is committed in its 26.3 state.
- Mixins that only exist on some versions are registered in `AdriftMixinPlugin` rather than `adrift.mixins.json`.
- `/adrift forcerift` makes any world count as the Rift, for trying features in singleplayer.

Layout:
- `features/` holds the modules, one config section each, grouped into the Boss, Party and Tracking tabs.
  A `Module` declares its options (`bool`, `int`, `double`, `choice`, `color`, `button`, `hud`) and listens with
  `on<Event>`, which only runs while it's enabled.
- `events/` is the event bus. `ConnectionMixin` posts packets and a server tick per ping (on the netty thread);
  `SlayerUtils` turns the fight into `SlayerEvent`s (spawn, mania start/end, complete).
- `config/` saves options to `config/adrift.json`; `config/gui/` is the Cascade config screen (from personalmod).
- `hud/` is the HUD element system and editor; `utils/render/` batches world geometry per frame.
