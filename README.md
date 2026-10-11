# Bulk Terminal

[中文文档（简体中文）](README_zh.md)

A MEGA Cells addon: provides the "Bulk Terminal", designed to improve the bulk-storage experience.

- Mod ID: `bulkterminal`
- License: LGPL-3.0-or-later

## Features

**Dedicated bulk-storage view**

- Only items marked by MEGA Bulk Cells are shown;
- Item counts display the network-wide total (MEGA Bulk Cells + other drives), consistent with the actually extractable amount — the number never jumps when extracting.

**Auto-marking of MEGA Bulk Cells**

- The first time an item is inserted, if no bulk cell accepts it, the terminal automatically writes that item's mark onto an unmarked MEGA Bulk Cell and retries the insertion;
- Inserting the same item again hits the marked cell directly, with no extra cost;
- Which cell gets marked follows the priority configured in the drive GUI (highest priority first);
- Auto-marking can be turned off in the terminal settings, reverting to vanilla MEGA behavior; the toggle is persisted.

**Compression management**

- Automatically installs compression cards from the network;
- Conveniently change the compression mode and type.

**One-click transfer** — compression management page:

- Clicking "Transfer All" automatically transfers already-marked items into MEGA Bulk Cells;
- Holding Shift and clicking "Transfer All" automatically transfers items above 20,000 in the network into MEGA Bulk Cells.

## Version Matrix

| Component | Version |
| --- | --- |
| Minecraft | 1.21.1 |
| NeoForge | 21.1.248 |
| AE2 | 19.2.15 |
| MEGA Cells | 4.11.0 |
| ModDevGradle | 2.0.107 |
| Java | 21 |

## Project Layout

```
bulk-terminal/
├── build.gradle.kts / settings.gradle.kts / gradle wrapper
├── src/main/java/com/dloi/bulkterminal/
│   ├── BulkTerminal.java          # mod entry point & registration
│   ├── part/                      # BulkTerminalPart: terminal cable part, overrides getInventory() to hook in the storage view
│   ├── storage/                   # core: BulkOnlyStorage (visibility / insert / extract),
│   │                              #   BulkCellScanner (PMCS scan + TTL cache),
│   │                              #   BulkCellMarker (locate free cell → write mark → trigger remount)
│   ├── menu/                      # BulkTerminalMenu: menu type
│   ├── client/                    # screen bindings, terminal & settings screens
│   └── definition/                # item / menu / creative tab registration
└── src/main/resources/
    ├── META-INF/neoforge.mods.toml
    ├── assets/ae2/screens/...     # terminal styles
    ├── assets/bulkterminal/...    # language files, models, textures
    └── data/bulkterminal/recipe/  # crafting recipe
```

## AI

This mod was developed with the assistance of AI.

## License

- The code is licensed under the LGPLv3 terms;
- The art assets are licensed under the CC BY-NC-SA 3.0 terms.
