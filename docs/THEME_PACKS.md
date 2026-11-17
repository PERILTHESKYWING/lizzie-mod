# Theme packs (groundwork)

Themes are already declarative: a folder under `theme/` with a `theme.txt` JSON file and the images
it names. This document fixes that format as the pack format for future user-made visual packs.
The default appearance is unchanged and no installer exists yet.

## Layout

```
theme/<pack-folder>/
  pack.json        optional manifest (below)
  theme.txt        theme properties read by featurecat.lizzie.theme.Theme
  board.png        images referenced by theme.txt
  black.png
  white.png
  background.png
```

## pack.json (schema 1)

```json
{
  "schemaVersion": 1,
  "id": "walnut",
  "name": "Walnut",
  "author": "Your name",
  "version": "1.0.0",
  "compatibleAppVersion": ">=2.5.0",
  "description": "Dark walnut board with slate and shell stones",
  "license": "CC-BY-4.0"
}
```

`compatibleAppVersion` accepts `""`, `2.5.3`, `>=2.5`, `>2.5`, `<=3`, `<3`.

## Supported properties

Images (inside the pack only): `board-image`, `background-image`, `black-stone-image`,
`white-stone-image`.

Board and stones: `use-pure-board`, `pure-board-color`, `use-pure-stone`, `show-stone-shadow`,
`shadow-size`, `stone-indicator-type`, `coordinate-size`, `coordinate-bold`.

Window and panels: `use-pure-background`, `pure-background-color`, `background-filter`,
`comment-background-color`, `comment-font-color`, `comment-font-size`, `var-panel-color`,
`glass-*`, `liquid-blur-radius`, `font-name`, `ui-font-name`, `winrate-font-name`.

Graphs and markers: `winrate-line-color`, `winrate-miss-line-color`, `winrate-stroke-width`,
`score-lead-stroke-width`, `scoremean-line-color`, `best-move-color`, `blunder-bar-color`,
`minimum-blunder-bar-width`, `blunder-winrate-thresholds`, `blunder-node-colors`,
`comment-node-color`, `show-comment-node-color`, `node-color-mode`,
`use-scorediff-in-variation-tree`, `scorediff-in-variation-tree-factor`.

The authoritative list is `ThemePack.KNOWN_KEYS`. Colours are `[r, g, b]` or `[r, g, b, a]`.

## Safety rules

`ThemePack.load` validates a pack without changing anything:

- packs contain no code; only JSON and images;
- image values must be relative png/jpg/gif paths that stay inside the pack folder (no absolute
  paths, drive letters, `..` or symbolic links), at most 16 MB each;
- unknown properties are warnings, so packs written for newer versions still load.

A future installer should: unpack into a new folder under `theme/` only (never over an existing
folder, application files or user data), run `ThemePack.load`, and refuse packs with errors or an
incompatible `compatibleAppVersion`. `ThemePack.scan(roots...)` lists packs for a picker.

## Extension points for new visual styles

- New image or colour slots: add the key to `Theme`, add it to `ThemePack.KNOWN_KEYS` (and to
  `IMAGE_KEYS` for images) and document it here. Renderers keep asking `Theme` for values, so
  packs never need renderer changes.
- Board overlays: `BoardRenderer` draws overlay objects (`CoachBoardMarks`,
  `OpeningExplorerMarks`) after the stones; a future pack property can style them through `Theme`.
