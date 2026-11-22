# Thource's Third Person Mod
Thource's Third Person mod is a third person camera mod for Wurm Unlimited (extended from https://github.com/tgrkzus/wurm-thirdperson).

## Requirements
- As with most WU mods, this mod depends on [Ago's mod loader](https://forum.wurmonline.com/index.php?/topic/134945-released-client-mod-loader).

## Installation
1. Download the latest version from https://github.com/Thource/thources-third-person-mod/releases/latest
2. Extract the contents of `thources-third-person-mod.zip` into your Wurm Unlimited `mods` folder.

## Usage
When ingame, open the console with F1 and type any of the following commands:

*Note: All commands must be prefixed with `tp`, for example: `tp toggle`.*

| Command               | Description                        |
| -------------------- | ----------------------------------- |
| `toggle`             | Turns third person on/off           |
| `enable`<br>`on`     | Turns third person on               |
| `disable`<br>`off`   | Turns third person off              |
| `zoom-in`            | Zooms in by 0.4                     |
| `zoom-out`           | Zooms out by 0.4                    |
| `set-zoom NUMBER`    | Sets zoom from 0 to 20              |
| `get-zoom`           | Gets the current zoom               |
| `set-xoffset NUMBER` | Sets xOffset to the specified value |
| `get-xoffset`        | Gets the current xOffset            |

The camera can zoom to a factor of 20 while retaining free pitch and rotation. Creatures and detailed trees fade when they block the local camera-to-player view. Ground items, projectiles, and terrain decorations are not faded. Billboard trees and merged structure meshes use localized cutouts where their batched render paths cannot safely fade a single instance. Terrain tiles sampled along an obstructed camera ray are cut out locally. During an underground third-person view, surface terrain decorations are suppressed and cave back-face culling is enabled for every cave tile, so cave ceilings and floors viewed from their outside side and cave wall backs are culled. When the camera is above the adjusted cave ceiling, the mod also opens a nearby surface terrain area.

## Build and install from source

From the project root, run:

```bash
./install.sh
```

The script runs `mvn clean install`, copies the built mod jar into the Wurm Unlimited `mods/thources-third-person-mod/` directory, and runs the game's `patcher.sh`. It keeps an existing `mods/thources-third-person-mod.properties` file so its configuration remains intact. If you intentionally want to replace that file with the project template, run `./install.sh --replace-properties`; the script saves a timestamped backup first.

To target a different Wurm installation, set `WURM_LAUNCHER_DIR` to its `WurmLauncher` directory.
