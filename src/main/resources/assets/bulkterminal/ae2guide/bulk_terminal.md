---
navigation:
  title: Bulk Terminal
  icon: bulk_terminal
  parent: index.md
  position: 010
categories:
  - bulkterminal
item_ids:
  - bulk_terminal
---

# Bulk Terminal

<Row>
  <ItemImage id="bulk_terminal" scale="3" />
</Row>
MEGA Bulk Item Storage Cells

**Bulk Terminal** is an add-on for MEGA Cells.
It provides an ME terminal: a dedicated terminal for marked storage and retrieval of
<ItemLink id="megacells:bulk_item_cell" />s.

## Placement & Crafting

<Row>
  <RecipeFor id="bulk_terminal" />
</Row>

Like any other terminal, attach it to an ME cable in the network to use it.


**Dedicated bulk-storage view**

- Only items marked by MEGA Bulk Cells are shown;
- Item counts display the network-wide total (MEGA Bulk Cells + other drives), consistent with the actually
  extractable amount — the number never jumps when extracting.

**Auto-marking of MEGA Bulk Cells**

- The first time an item is inserted, if no bulk cell accepts it, the terminal automatically writes that item's mark
  onto an unmarked MEGA Bulk Cell and retries the insertion;
- Inserting the same item again hits the marked cell directly, with no extra cost;
- Which cell gets marked follows the priority configured in the drive GUI (highest priority first);
- Auto-marking can be turned off in the terminal settings, reverting to vanilla MEGA behavior; the toggle is
  persisted.

**Compression management**

- Automatically installs compression cards from the network;
- Conveniently change the compression mode and type.

**One-click transfer** — compression management page:

- Clicking "Transfer All" automatically transfers already-marked items into MEGA Bulk Cells;
- Holding Shift and clicking "Transfer All" automatically transfers items above 20,000 in the network into MEGA Bulk
  Cells.

## Storage Rules

- **Visibility**: the terminal only shows items that some bulk cell has marked or is storing. Everything else is
  hidden — that is a job for regular terminals.
- **Extraction**: taken from bulk cells first; any shortfall is automatically topped up from regular drives in the
  network.
- **Compression chains**: a bulk cell fitted with a Compression Card also claims the variants of the same compression
  chain (e.g. a cell marked for Iron Ingots also accepts Iron Blocks). The terminal's duplicate-marking detection
  respects this and will not dedicate another cell to a chain variant.
