# Opening Explorer

A compact panel that shows which moves players chose from the position on the main board, counted
from your own imported SGF collections. It never runs an engine: popularity is not strength.

## Use

1. Press **K**, or use **View → Panel → Opening Explorer (K)** or function search, to open the panel at the right edge of the window (about
   280 px; the board keeps the rest). K, the × button or the same menu item closes it. The panel
   remembers whether it was open.
2. **Import… → SGF / ZIP files…** picks SGF files and/or ZIP archives of SGF files (several at
   once) and asks for a collection name (new or existing). **Import… → Fox accounts…** downloads up
   to the 200 newest games of each Fox (野狐) account you name (separate several with commas) into a
   collection, using the same Fox service as the Fox Kifu window; no login is needed. Import runs in the background with progress and a Cancel
   button; games finished before a cancel are kept. Duplicate records are skipped (or linked to the
   extra collection) and unreadable games are reported, not fatal.
3. Navigate the game as usual. For every position the panel lists each recorded next move as a bar
   (its share of the games), with the percentage and the number of games, most popular first, and
   the sample size above the list. The same percentages appear on the board.
   Selecting a row highlights the move on the board; nothing is played. **⋯ → Colours…** sets
   the colours of the board markers, the bars and the selected move, and links to KataGo's
   suggested-move colours (Settings → Theme → Best move colour). Double-clicking a row lists
   the games behind it (players with ranks, date, event, result, source file), newest first,
   counted exactly as the row is counted.
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
- **Duplicates:** games whose main line is identical (30 moves or more) are one game, even when
  player names, dates or file names differ, and so are mirrored or rotated copies of the same
  record. The earliest copy is kept and joins the other copy's collections. This runs after every
  import and once on databases created by earlier versions. Shorter games are only merged when
  their moves, players, date and result all match.
- **Names on symmetric boards:** when several points are equivalent (the empty board, symmetric
  shapes), the list and the board use the one nearest the top-right corner, so the 4-4 point is
  Q16 and the 3-4 point is R16. Otherwise moves are shown where they would really be played.

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

## Notes

- While an AI Coach game runs, the coach panel uses the right edge and the explorer is hidden; it
  comes back when the coaching game ends.
- "Index variations in new imports" applies to files imported after it is switched on; re-import
  a collection to add variations to games imported before.
- Positions match on simple ko only; superko history is not part of a position.
- Files over 8 MB and ZIP archives with more than 200,000 entries are skipped and reported.
