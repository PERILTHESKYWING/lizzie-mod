# Opening Explorer

A compact panel that shows which moves players chose from the position on the main board, counted
from your own imported SGF collections. It never runs an engine: popularity is not strength.

## Use

1. **View → Panel → Opening Explorer** opens the panel at the right edge of the window (about
   280 px; the board keeps the rest). The × button or the same menu item closes it. The panel
   remembers whether it was open.
2. **Import…** picks SGF files and/or ZIP archives of SGF files (several at once) and asks for a
   collection name (new or existing). Import runs in the background with progress and a Cancel
   button; games finished before a cancel are kept. Duplicate records are skipped (or linked to the
   extra collection) and unreadable games are reported, not fatal.
3. Navigate the game as usual. For every position the panel lists each recorded next move with the
   number of games and the percentage, most popular first, and the sample size above the list.
   Selecting a row highlights the move on the board; nothing is played.
4. The collection button selects which collections take part (several may be ticked).
   **⋯ → Filter games…** restricts by player, rank, event and year range. Games without a date are
   excluded only while a year range is set.

## What is counted

- **Position identity:** board size, black and white stones, the player to move and the simple-ko
  point, normalised over the board's symmetries (all eight on square boards; on rectangular boards
  only the four that keep the size). Colours are never swapped. Transpositions match because only
  the resulting position matters.
- **Default statistic (main line):** each distinct game counts once per position, using the move
  played at its first occurrence in the main line.
  `percentage = games choosing the move / games reaching the position × 100`.
  Games that ended in the position are in the denominator and shown as "ended here", so the
  moves plus the ended games add up to 100 %. Pass is its own row.
- **Raw counts** (⋯ → Show raw occurrence counts) also include repeats of a position inside one
  game; they never change the default percentages.
- **Variations** are optional and kept separate: ⋯ → Index variations in new imports stores them,
  ⋯ → Count SGF variations includes them in the statistics. With variations a game still counts
  once per move, so shares may add up to more than 100 %.
- **Symmetric positions:** when the board itself is symmetric, equivalent moves (for example the
  four 3-3 answers to tengen) are one row and are drawn on one representative point.
- **Duplicates:** a game's identity is its board size, setup stones, main-line moves and the
  PB/PW/DT/RE properties; importing the same record again changes nothing.

## Storage

Everything is local, under the work directory in `opening-explorer/`:

| File | Content |
| --- | --- |
| `library.json` | collections, counters, the current games file and index segments (written last, atomically) |
| `games-N.jsonl` | one line per game: id, fingerprint, collections, SGF metadata |
| `seg-N.bin` | immutable index segments sorted by canonical position key |
| `explorer-settings.json` | panel preferences |

Each import adds a segment, so existing games are never re-indexed; segments are merged when more
than eight accumulate. Your SGF files are only read, never changed or copied.

## Implementation notes

- `featurecat.lizzie.explorer` has no UI or global-state dependencies: `SgfReader` (a minimal
  side-effect-free SGF reader; the main `SGFParser` writes the global board size and Zobrist tables
  while parsing, so it cannot run on a background thread), `GoPosition` (captures, suicide, ko),
  `BoardSymmetry`, `CanonicalPosition` (fixed-seed 128-bit Zobrist per symmetry, minimum is the
  key, stabiliser-aware move normalisation), `SgfGameIndexer`, `OpeningDatabase`,
  `OpeningImporter` and `OpeningExplorerService` (one owner thread for all database work).
- KataGo's `symHash` is not used: it would need an engine query per position and does not include
  the ko/history information required here.
- Tests: `OpeningExplorerCorrectnessTest` (synthetic fixtures with hand-computed counts for every
  symmetry, transpositions, side to move, sizes, colour inversion, passes, repeats, duplicates,
  variations, denominators, metadata, ZIP, persistence, segment merge, cancellation and a
  1,200-game import) and `OpeningExplorerPanelTest`.
