package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.explorer.OpeningDatabase;
import featurecat.lizzie.explorer.OpeningImporter;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.SGFParser;
import featurecat.lizzie.rules.Zobrist;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The panel reads the real game tree, queries the index and maps moves into the board's view. */
class OpeningExplorerPanelTest {
  @TempDir Path tmp;
  private Config previousConfig;
  private int previousWidth;
  private int previousHeight;
  private BoardRenderer previousRenderer;

  @BeforeEach
  void setUp() {
    previousConfig = Lizzie.config;
    previousWidth = Board.boardWidth;
    previousHeight = Board.boardHeight;
    previousRenderer = LizzieFrame.boardRenderer;
    LizzieFrame.boardRenderer = null;
    Lizzie.config = ConfigTestHelper.createForTests(tmp.resolve("work"));
    Board.boardWidth = 19;
    Board.boardHeight = 19;
    Zobrist.init();
  }

  @AfterEach
  void tearDown() throws Exception {
    SwingUtilities.invokeAndWait(() -> {});
    Lizzie.config = previousConfig;
    Board.boardWidth = previousWidth;
    Board.boardHeight = previousHeight;
    LizzieFrame.boardRenderer = previousRenderer;
    Zobrist.init();
  }

  private void seed(Path dir) throws Exception {
    OpeningDatabase db = OpeningDatabase.open(dir);
    int id = db.createCollection("pros");
    List<Path> files =
        List.of(
            write("(;SZ[19]PB[a];B[pd];W[dd];B[pp])"),
            write("(;SZ[19]PB[b];B[pd];W[dd];B[pp])"),
            write("(;SZ[19]PB[c];B[pd];W[dd];B[dq])"));
    OpeningImporter.importFiles(db, files, id, false, (a, b, c, d) -> {}, () -> false);
  }

  private int fileNo;

  private Path write(String sgf) throws Exception {
    Path p = tmp.resolve("f" + (fileNo++) + ".sgf");
    Files.writeString(p, sgf, StandardCharsets.UTF_8);
    return p;
  }

  private static void waitFor(BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + 10_000_000_000L;
    AtomicReference<Boolean> ok = new AtomicReference<>(false);
    while (System.nanoTime() < deadline) {
      SwingUtilities.invokeAndWait(() -> ok.set(condition.getAsBoolean()));
      if (ok.get()) return;
      Thread.onSpinWait();
    }
    throw new AssertionError("condition not reached");
  }

  @Test
  void showsPopularMovesForTheDisplayedPositionInItsOwnOrientation() throws Exception {
    Path dir = tmp.resolve("explorer");
    seed(dir);
    // The displayed game is the same opening rotated by 180 degrees: B dp, W pp.
    BoardHistoryList history = SGFParser.parseSgf("(;SZ[19];B[dp];W[pp])", false);
    while (history.next().isPresent()) {}
    AtomicReference<OpeningExplorerPanel> panel = new AtomicReference<>();
    int[] relayouts = {0};
    SwingUtilities.invokeAndWait(
        () ->
            panel.set(
                new OpeningExplorerPanel(dir, () -> history, () -> relayouts[0]++, () -> {})));
    SwingUtilities.invokeAndWait(() -> panel.get().setOpen(true));
    OpeningExplorerPanel p = panel.get();
    waitFor(() -> p.rowCount() == 2);
    SwingUtilities.invokeAndWait(
        () -> {
          // Main line pd dd pp, rotated 180: pp -> dd (D16), dq -> pc (Q17).
          assertEquals(Board.convertCoordinatesToName(3, 3), p.rowAt(0).move());
          assertEquals(66.666, p.rowAt(0).percent(), 0.01);
          assertTrue(p.rowAt(0).top());
          assertTrue(p.rowAt(0).detail().startsWith("2 "), p.rowAt(0).detail());
          assertEquals(Board.convertCoordinatesToName(15, 2), p.rowAt(1).move());
          assertFalse(p.rowAt(1).top());
          List<OpeningExplorerMarks.Mark> marks = p.marks().marks();
          assertEquals(2, marks.size());
          assertEquals(3, marks.get(0).x());
          assertEquals(3, marks.get(0).y());
          assertFalse(marks.get(0).selected());
          p.selectRow(1);
          assertTrue(p.marks().marks().get(1).selected());
          assertEquals(1, relayouts[0]);
        });
    assertTrue(Files.exists(dir.resolve("explorer-settings.json")));
    SwingUtilities.invokeAndWait(() -> panel.get().setOpen(false));
    SwingUtilities.invokeAndWait(() -> assertTrue(panel.get().marks().marks().isEmpty()));
  }

  @Test
  void importsFromThePanelAndListsTheGamesBehindAMove() throws Exception {
    Path dir = tmp.resolve("explorer-import");
    BoardHistoryList history = SGFParser.parseSgf("(;SZ[19];B[pd];W[dd])", false);
    while (history.next().isPresent()) {}
    List<Path> files =
        List.of(
            write("(;SZ[19]PB[Old]BR[9p]PW[w1]DT[2001-05-01]EV[Cup A]RE[B+R];B[pd];W[dd];B[pp])"),
            write("(;SZ[19]PB[New]PW[w2]DT[2024-02-02]RE[W+1.5];B[pd];W[dd];B[pp])"),
            write("(;SZ[19]PB[Other]PW[w3];B[pd];W[dd];B[dq])"));
    AtomicReference<OpeningExplorerPanel> panel = new AtomicReference<>();
    SwingUtilities.invokeAndWait(
        () -> {
          panel.set(new OpeningExplorerPanel(dir, () -> history, () -> {}, () -> {}));
          panel.get().setOpen(true);
          panel.get().startImport(files, "Pros");
        });
    OpeningExplorerPanel p = panel.get();
    waitFor(() -> p.rowCount() == 2 && p.rowAt(0).detail().startsWith("2 "));
    SwingUtilities.invokeAndWait(() -> panel.get().showGames(0));
    waitFor(() -> panel.get().lastGamesWindow() != null);
    SwingUtilities.invokeAndWait(
        () -> {
          JTable games = panel.get().lastGamesWindow().table();
          assertEquals(2, games.getRowCount());
          assertEquals("New", games.getValueAt(0, 0)); // newest first
          assertEquals("Old (9p)", games.getValueAt(1, 0));
          assertEquals("Cup A", games.getValueAt(1, 3));
          assertEquals("B+R", games.getValueAt(1, 4));
        });
    // Hover preview: the stored record is replayed to this position (2 moves played, B[pp] next).
    java.util.concurrent.atomic.AtomicReference<OpeningGamesWindow.Lookup> lookup =
        new java.util.concurrent.atomic.AtomicReference<>();
    SwingUtilities.invokeAndWait(() -> panel.get().lastGamesWindow().snapshotFor(0, lookup::set));
    waitFor(() -> lookup.get() != null);
    assertEquals(2, lookup.get().snapshot().movesPlayed());
    assertEquals(
        featurecat.lizzie.explorer.SgfGameIndexer.class.getSimpleName().length() > 0
            ? 15 + 15 * 19
            : 0,
        lookup.get().snapshot().nextMove()); // pp = (15, 15)
    assertTrue(lookup.get().record().contains("PB[New]"));
    assertTrue(Files.exists(dir.resolve("library.json")));
  }
}
