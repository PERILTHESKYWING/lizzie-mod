package featurecat.lizzie.gui;

import featurecat.lizzie.explorer.GameMetadata;
import featurecat.lizzie.explorer.GameReplay;
import featurecat.lizzie.explorer.OpeningDatabase;
import featurecat.lizzie.explorer.PositionKey;
import java.awt.BorderLayout;
import java.awt.Point;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.swing.BorderFactory;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import javax.swing.table.DefaultTableModel;

/**
 * The games behind one Opening Explorer row. Hovering a game shows a small board of it at the
 * explorer's position with the move it played; double-clicking opens it in the main window there.
 */
final class OpeningGamesWindow {
  /** Loads a game's stored record; answers null when none is stored. */
  interface RecordSource {
    void load(int gameId, Consumer<String> result);
  }

  private final List<OpeningDatabase.GameRef> games;
  private final PositionKey key;
  private final RecordSource records;
  private final BiConsumer<String, Integer> open;
  private final Function<String, String> text;
  private final Map<Integer, String> cache = new HashMap<>();
  private final JTable table;
  private final MiniBoard board = new MiniBoard(220);
  private final JLabel hint = new JLabel();
  private JWindow popup;
  private int hoverRow = -1;
  private final Timer hoverTimer;

  OpeningGamesWindow(
      Window owner,
      String title,
      List<OpeningDatabase.GameRef> games,
      PositionKey key,
      RecordSource records,
      BiConsumer<String, Integer> open,
      Function<String, String> text) {
    this.games = games;
    this.key = key;
    this.records = records;
    this.open = open;
    this.text = text;
    String[] columns = {
      text.apply("colBlack"),
      text.apply("colWhite"),
      text.apply("colDate"),
      text.apply("colEvent"),
      text.apply("colResult"),
      text.apply("colSource")
    };
    Object[][] rows = new Object[games.size()][];
    for (int i = 0; i < games.size(); i++) {
      GameMetadata g = games.get(i).metadata();
      rows[i] =
          new Object[] {
            withRank(g.blackPlayer(), g.blackRank()),
            withRank(g.whitePlayer(), g.whiteRank()),
            g.date(),
            g.event(),
            g.result(),
            g.source()
          };
    }
    table =
        new JTable(new DefaultTableModel(rows, columns)) {
          @Override
          public boolean isCellEditable(int r, int c) {
            return false;
          }
        };
    table.setAutoCreateRowSorter(true);
    table.setFillsViewportHeight(true);
    ExplorerTheme.table(table);
    hoverTimer = new Timer(250, e -> showPreview());
    hoverTimer.setRepeats(false);
    MouseAdapter mouse =
        new MouseAdapter() {
          @Override
          public void mouseMoved(MouseEvent e) {
            int row = table.rowAtPoint(e.getPoint());
            if (row != hoverRow) {
              hoverRow = row;
              hidePreview();
              if (row >= 0) hoverTimer.restart();
            }
          }

          @Override
          public void mouseExited(MouseEvent e) {
            hoverRow = -1;
            hoverTimer.stop();
            hidePreview();
          }

          @Override
          public void mouseClicked(MouseEvent e) {
            int row = table.rowAtPoint(e.getPoint());
            if (e.getClickCount() == 2 && row >= 0) openRow(row);
          }
        };
    table.addMouseListener(mouse);
    table.addMouseMotionListener(mouse);
    hint.setText(text.apply("gamesHover"));
    ExplorerTheme.label(hint, true, 12f, java.awt.Font.PLAIN);
    hint.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
    if (java.awt.GraphicsEnvironment.isHeadless()) return;
    JDialog dialog = new JDialog(owner, title, java.awt.Dialog.ModalityType.MODELESS);
    JPanel content = new JPanel(new BorderLayout());
    content.setBackground(ExplorerTheme.SURFACE);
    JScrollPane scroll = new JScrollPane(table);
    scroll.setBorder(BorderFactory.createEmptyBorder());
    scroll.getViewport().setBackground(ExplorerTheme.SURFACE);
    content.add(scroll, BorderLayout.CENTER);
    content.add(hint, BorderLayout.SOUTH);
    dialog.setContentPane(content);
    dialog.setSize(760, 400);
    dialog.setLocationRelativeTo(owner);
    dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
    dialog.addWindowListener(
        new java.awt.event.WindowAdapter() {
          @Override
          public void windowClosed(java.awt.event.WindowEvent e) {
            hidePreview();
          }
        });
    dialog.setVisible(true);
  }

  JTable table() {
    return table;
  }

  private int gameIndex(int viewRow) {
    return table.convertRowIndexToModel(viewRow);
  }

  /** Finds the game's position for a table row (record loaded on demand, then cached). */
  void snapshotFor(int viewRow, Consumer<Lookup> result) {
    OpeningDatabase.GameRef ref = games.get(gameIndex(viewRow));
    Consumer<String> withRecord =
        record -> {
          cache.put(ref.id(), record == null ? "" : record);
          if (record == null || record.isEmpty()) {
            result.accept(new Lookup(null, null));
            return;
          }
          result.accept(new Lookup(record, GameReplay.find(record, key)));
        };
    if (cache.containsKey(ref.id())) {
      withRecord.accept(cache.get(ref.id()));
    } else if (!ref.hasRecord()) {
      withRecord.accept(null);
    } else {
      records.load(ref.id(), withRecord);
    }
  }

  /** A stored record and the matching position in it (either may be null). */
  record Lookup(String record, GameReplay.Snapshot snapshot) {}

  /** Caption under the preview: the move number and what was played. */
  String caption(Lookup l) {
    if (l.record() == null) return text.apply("noRecord");
    if (l.snapshot() == null) return text.apply("noMatch");
    return java.text.MessageFormat.format(
        text.apply("previewCaption"), l.snapshot().movesPlayed() + 1);
  }

  void openRow(int viewRow) {
    snapshotFor(
        viewRow,
        l -> {
          if (l.record() != null) {
            open.accept(l.record(), l.snapshot() == null ? 0 : l.snapshot().movesPlayed());
          } else {
            hint.setText(text.apply("noRecord"));
          }
        });
  }

  private void showPreview() {
    int row = hoverRow;
    if (row < 0 || !table.isShowing()) return;
    snapshotFor(
        row,
        l -> {
          if (row != hoverRow) return;
          board.show(l.snapshot(), caption(l));
          if (popup == null) {
            popup = new JWindow(SwingUtilities.getWindowAncestor(table));
            JPanel p = new JPanel(new BorderLayout());
            p.setBorder(BorderFactory.createLineBorder(ExplorerTheme.BORDER));
            p.add(board, BorderLayout.CENTER);
            popup.setContentPane(p);
            popup.pack();
          }
          Point mouse = table.getMousePosition();
          Point at = mouse == null ? new Point(0, 0) : mouse;
          SwingUtilities.convertPointToScreen(at, table);
          popup.setLocation(at.x + 18, at.y + 12);
          popup.setVisible(true);
        });
  }

  private void hidePreview() {
    if (popup != null) popup.setVisible(false);
  }

  private static String withRank(String player, String rank) {
    return rank.isEmpty() ? player : player + " (" + rank + ")";
  }
}
