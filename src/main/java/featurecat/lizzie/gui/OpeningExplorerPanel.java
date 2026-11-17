package featurecat.lizzie.gui;

import featurecat.lizzie.explorer.BoardPositionAdapter;
import featurecat.lizzie.explorer.CanonicalPosition;
import featurecat.lizzie.explorer.GoPosition;
import featurecat.lizzie.explorer.OpeningDatabase;
import featurecat.lizzie.explorer.OpeningExplorerService;
import featurecat.lizzie.explorer.OpeningExplorerSettings;
import featurecat.lizzie.explorer.OpeningFilter;
import featurecat.lizzie.explorer.OpeningStats;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.Stone;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.io.File;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

/**
 * The Opening Explorer: a narrow panel docked at the right edge of the main window that lists the
 * moves played from the current position in the user's imported SGF collections. It never runs an
 * engine; statistics come from the local index ({@link OpeningExplorerService}).
 *
 * <p>Event thread only. The frame calls {@link #boardChanged()} from its refresh.
 */
final class OpeningExplorerPanel extends JPanel {
  static final int WIDTH = 280;
  private static final int MAX_BOARD_MARKS = 12;
  private static final Color BACKGROUND = new Color(0xf7, 0xf7, 0xf5);
  private static final Color LINE = new Color(0xd8, 0xd8, 0xd4);
  private static final Color MUTED = new Color(0x60, 0x60, 0x60);

  private final Supplier<BoardHistoryList> history;
  private final Runnable relayout;
  private final Runnable repaintBoard;
  private final OpeningExplorerService service;
  private final OpeningExplorerSettings settings;
  private final OpeningExplorerMarks marks = new OpeningExplorerMarks();
  private final ResourceBundle bundle = ResourceBundle.getBundle("l10n.DisplayStrings");

  private final JButton collectionsButton = new JButton();
  private final JLabel summary = new JLabel(" ");
  private final JLabel status = new JLabel(" ");
  private final JProgressBar progress = new JProgressBar();
  private final JButton cancelImport = new JButton();
  private final MoveTableModel model = new MoveTableModel();
  private final JTable table = new JTable(model);

  private List<OpeningDatabase.Collection> collections = List.of();
  private CanonicalPosition position;
  private OpeningStats stats;
  private String lastQueryIdentity;
  private int dataVersion;
  private OpeningExplorerService.ImportHandle runningImport;

  OpeningExplorerPanel(
      Path directory,
      Supplier<BoardHistoryList> history,
      Runnable relayout,
      Runnable repaintBoard) {
    super(new BorderLayout());
    this.history = history;
    this.relayout = relayout;
    this.repaintBoard = repaintBoard;
    this.service = new OpeningExplorerService(directory, SwingUtilities::invokeLater);
    this.settings = OpeningExplorerSettings.load(directory);
    setOpaque(true);
    setBackground(BACKGROUND);
    setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, LINE));
    add(buildHeader(), BorderLayout.NORTH);
    add(buildTable(), BorderLayout.CENTER);
    add(buildFooter(), BorderLayout.SOUTH);
    // Reopen where the user left it; the frame's first refresh runs the first query.
    setVisible(settings.visible);
    refreshCollections();
  }

  OpeningExplorerMarks marks() {
    return marks;
  }

  /** True when the user left the panel open last time. */
  boolean wasOpen() {
    return settings.visible;
  }

  void toggle() {
    setOpen(!isVisible());
  }

  void setOpen(boolean open) {
    setVisible(open);
    settings.visible = open;
    settings.save(service.directory());
    if (open) {
      lastQueryIdentity = null;
      boardChanged();
    } else {
      updateMarks();
    }
    relayout.run();
  }

  // --------------------------------------------------------------------------- layout

  private JPanel buildHeader() {
    JPanel header = new JPanel();
    header.setOpaque(false);
    header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
    header.setBorder(BorderFactory.createEmptyBorder(8, 10, 4, 8));

    JPanel titleRow = new JPanel(new BorderLayout(4, 0));
    titleRow.setOpaque(false);
    JLabel title = new JLabel(text("title"));
    title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() + 1f));
    titleRow.add(title, BorderLayout.CENTER);
    JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
    buttons.setOpaque(false);
    JButton importButton = smallButton(text("import"));
    importButton.addActionListener(e -> chooseAndImport());
    JButton more = smallButton("⋯");
    more.setToolTipText(text("more"));
    more.addActionListener(e -> showMoreMenu(more));
    JButton close = smallButton("×");
    close.setToolTipText(text("close"));
    close.addActionListener(e -> setOpen(false));
    buttons.add(importButton);
    buttons.add(more);
    buttons.add(close);
    titleRow.add(buttons, BorderLayout.EAST);
    titleRow.setAlignmentX(Component.LEFT_ALIGNMENT);
    header.add(titleRow);
    header.add(Box.createVerticalStrut(6));

    collectionsButton.setHorizontalAlignment(SwingConstants.LEFT);
    collectionsButton.setFocusable(false);
    collectionsButton.setMargin(new java.awt.Insets(2, 6, 2, 6));
    collectionsButton.addActionListener(e -> showCollectionsMenu());
    collectionsButton.setAlignmentX(Component.LEFT_ALIGNMENT);
    collectionsButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
    header.add(collectionsButton);
    header.add(Box.createVerticalStrut(6));
    summary.setAlignmentX(Component.LEFT_ALIGNMENT);
    header.add(summary);
    return header;
  }

  private JScrollPane buildTable() {
    table.setFillsViewportHeight(true);
    table.setShowVerticalLines(false);
    table.setGridColor(LINE);
    table.setRowHeight(Math.max(20, table.getRowHeight() + 2));
    table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    table.getTableHeader().setReorderingAllowed(false);
    table
        .getSelectionModel()
        .addListSelectionListener(
            e -> {
              if (!e.getValueIsAdjusting()) updateMarks();
            });
    DefaultTableCellRenderer right = new DefaultTableCellRenderer();
    right.setHorizontalAlignment(SwingConstants.RIGHT);
    table.setDefaultRenderer(Integer.class, right);
    table.setDefaultRenderer(Double.class, new PercentRenderer());
    applyColumnWidths();
    JScrollPane scroll = new JScrollPane(table);
    scroll.setBorder(BorderFactory.createMatteBorder(1, 0, 1, 0, LINE));
    scroll.getViewport().setBackground(Color.WHITE);
    return scroll;
  }

  private JPanel buildFooter() {
    JPanel footer = new JPanel(new BorderLayout(4, 2));
    footer.setOpaque(false);
    footer.setBorder(BorderFactory.createEmptyBorder(4, 10, 6, 8));
    status.setForeground(MUTED);
    status.setFont(status.getFont().deriveFont(status.getFont().getSize2D() - 1f));
    footer.add(status, BorderLayout.NORTH);
    progress.setVisible(false);
    progress.setStringPainted(true);
    cancelImport.setText(text("cancel"));
    cancelImport.setVisible(false);
    cancelImport.addActionListener(
        e -> {
          if (runningImport != null) runningImport.cancel();
        });
    footer.add(progress, BorderLayout.CENTER);
    footer.add(cancelImport, BorderLayout.EAST);
    return footer;
  }

  private static JButton smallButton(String label) {
    JButton b = new JButton(label);
    b.setFocusable(false);
    b.setMargin(new java.awt.Insets(1, 6, 1, 6));
    return b;
  }

  private void applyColumnWidths() {
    int[] widths = settings.showRawCounts ? new int[] {52, 54, 56, 44} : new int[] {60, 70, 70};
    for (int i = 0; i < widths.length && i < table.getColumnCount(); i++) {
      table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
    }
  }

  // --------------------------------------------------------------------------- board queries

  /** Re-queries when the displayed position, filter or data changed. */
  void boardChanged() {
    if (!isVisible()) return;
    BoardHistoryList h = history.get();
    if (h == null || h.getCurrentHistoryNode() == null) return;
    BoardHistoryNode node = h.getCurrentHistoryNode();
    BoardData data = node.getData();
    if (data == null || data.stones == null) return;
    Stone[] parent = node.previous().map(p -> p.getData().stones).orElse(null);
    CanonicalPosition p =
        BoardPositionAdapter.canonical(
            Board.boardWidth,
            Board.boardHeight,
            data.stones,
            parent,
            data.lastMove,
            data.blackToPlay);
    OpeningFilter filter = settings.filter();
    String identity = p.key() + "|" + filter + "|" + settings.includeVariations + "|" + dataVersion;
    if (identity.equals(lastQueryIdentity)) {
      // Same canonical position (possibly another orientation): only the display mapping changes.
      if (stats != null && position != p) {
        position = p;
        showStats();
      }
      return;
    }
    lastQueryIdentity = identity;
    service.query(
        p,
        filter,
        settings.includeVariations,
        r -> {
          position = r.position();
          stats = r.stats();
          if (r.error() != null) status.setText(format("error", r.error()));
          showStats();
        });
  }

  private void showStats() {
    if (stats == null || position == null) return;
    int selectedMove = selectedCanonicalMove();
    model.set(position, stats, settings.showRawCounts);
    applyColumnWidths();
    int total = totalGames();
    if (total == 0) {
      summary.setText(text("emptyDatabase"));
    } else if (stats.games() == 0) {
      summary.setText(text("noGames"));
    } else {
      StringBuilder s = new StringBuilder("<html><b>");
      s.append(format("sample", String.format(Locale.ROOT, "%,d", stats.games()))).append("</b>");
      if (stats.endedGames() > 0) {
        s.append(" &middot; ").append(format("ended", stats.endedGames()));
      }
      if (settings.showRawCounts) {
        s.append("<br>").append(format("raw", stats.rawOccurrences()));
      }
      if (settings.includeVariations) s.append("<br>").append(text("variationsNote"));
      summary.setText(s.append("</html>").toString());
    }
    for (int row = 0; row < model.getRowCount(); row++) {
      if (model.moveAt(row).canonicalMove() == selectedMove) {
        table.getSelectionModel().setSelectionInterval(row, row);
        break;
      }
    }
    updateMarks();
  }

  private int selectedCanonicalMove() {
    int row = table.getSelectedRow();
    return row >= 0 && row < model.getRowCount() ? model.moveAt(row).canonicalMove() : -100;
  }

  private void updateMarks() {
    List<OpeningExplorerMarks.Mark> list = new ArrayList<>();
    if (isVisible() && settings.showOnBoard && stats != null && position != null) {
      int selected = selectedCanonicalMove();
      int shown = 0;
      for (OpeningStats.MoveStat m : stats.moves()) {
        boolean isSelected = m.canonicalMove() == selected;
        if (m.isPass() || (shown >= MAX_BOARD_MARKS && !isSelected)) continue;
        int point = position.toDisplay(m.canonicalMove());
        int x = point % position.width();
        int y = point / position.width();
        String label = String.format(Locale.ROOT, "%.0f", stats.percent(m));
        list.add(new OpeningExplorerMarks.Mark(x, y, label, isSelected));
        shown++;
      }
    }
    marks.set(list);
    if (LizzieFrame.boardRenderer != null) LizzieFrame.boardRenderer.setOpeningMarks(marks);
    repaintBoard.run();
  }

  private int totalGames() {
    int total = 0;
    for (OpeningDatabase.Collection c : collections) total += c.games();
    return total;
  }

  // --------------------------------------------------------------------------- collections

  private void refreshCollections() {
    service.collections(
        list -> {
          collections = list;
          // Forget selections of collections that no longer exist.
          Set<Integer> ids = new LinkedHashSet<>();
          for (OpeningDatabase.Collection c : list) ids.add(c.id());
          if (settings.collections.retainAll(ids)) settings.save(service.directory());
          updateCollectionsButton();
          dataVersion++;
          lastQueryIdentity = null;
          boardChanged();
          if (stats != null) showStats();
        });
  }

  private void updateCollectionsButton() {
    String label;
    if (settings.collections.isEmpty()) {
      label = text("collectionsAll");
    } else if (settings.collections.size() == 1) {
      int id = settings.collections.iterator().next();
      label =
          collections.stream()
              .filter(c -> c.id() == id)
              .map(OpeningDatabase.Collection::name)
              .findFirst()
              .orElse(text("collectionsAll"));
    } else {
      label = format("collectionsSome", settings.collections.size());
    }
    if (settings.filter().restrictsMetadata()) label += " · " + text("filterActive");
    collectionsButton.setText(label + " ▾");
  }

  private void showCollectionsMenu() {
    JPopupMenu menu = new JPopupMenu();
    JCheckBoxMenuItem all = new JCheckBoxMenuItem(text("collectionsAll"));
    all.setSelected(settings.collections.isEmpty());
    all.addActionListener(e -> selectCollections(Set.of()));
    menu.add(all);
    if (!collections.isEmpty()) menu.addSeparator();
    for (OpeningDatabase.Collection c : collections) {
      JCheckBoxMenuItem item =
          new JCheckBoxMenuItem(
              c.name() + "  (" + String.format(Locale.ROOT, "%,d", c.games()) + ")");
      item.setSelected(settings.collections.contains(c.id()));
      item.addActionListener(
          e -> {
            Set<Integer> next = new LinkedHashSet<>(settings.collections);
            if (item.isSelected()) next.add(c.id());
            else next.remove(c.id());
            selectCollections(next);
          });
      menu.add(item);
    }
    menu.show(collectionsButton, 0, collectionsButton.getHeight());
  }

  private void selectCollections(Set<Integer> ids) {
    settings.collections = new LinkedHashSet<>(ids);
    settings.save(service.directory());
    updateCollectionsButton();
    lastQueryIdentity = null;
    boardChanged();
  }

  private void showMoreMenu(Component anchor) {
    JPopupMenu menu = new JPopupMenu();
    JMenuItem filter = new JMenuItem(text("filter"));
    filter.addActionListener(e -> editFilter());
    menu.add(filter);
    JCheckBoxMenuItem variations = new JCheckBoxMenuItem(text("includeVariations"));
    variations.setSelected(settings.includeVariations);
    variations.addActionListener(
        e -> {
          settings.includeVariations = variations.isSelected();
          saveAndRequery();
        });
    menu.add(variations);
    JCheckBoxMenuItem indexVariations = new JCheckBoxMenuItem(text("indexVariations"));
    indexVariations.setSelected(settings.indexVariations);
    indexVariations.addActionListener(
        e -> {
          settings.indexVariations = indexVariations.isSelected();
          settings.save(service.directory());
        });
    menu.add(indexVariations);
    JCheckBoxMenuItem onBoard = new JCheckBoxMenuItem(text("showOnBoard"));
    onBoard.setSelected(settings.showOnBoard);
    onBoard.addActionListener(
        e -> {
          settings.showOnBoard = onBoard.isSelected();
          settings.save(service.directory());
          updateMarks();
        });
    menu.add(onBoard);
    JCheckBoxMenuItem raw = new JCheckBoxMenuItem(text("showRaw"));
    raw.setSelected(settings.showRawCounts);
    raw.addActionListener(
        e -> {
          settings.showRawCounts = raw.isSelected();
          settings.save(service.directory());
          showStats();
        });
    menu.add(raw);
    menu.addSeparator();
    JMenuItem rename = new JMenuItem(text("renameCollection"));
    rename.setEnabled(!collections.isEmpty());
    rename.addActionListener(e -> renameCollection());
    menu.add(rename);
    JMenuItem delete = new JMenuItem(text("deleteCollection"));
    delete.setEnabled(!collections.isEmpty() && runningImport == null);
    delete.addActionListener(e -> deleteCollection());
    menu.add(delete);
    JMenuItem folder = new JMenuItem(text("openFolder"));
    folder.addActionListener(e -> openFolder());
    menu.add(folder);
    menu.show(anchor, 0, anchor.getHeight());
  }

  private void saveAndRequery() {
    settings.save(service.directory());
    updateCollectionsButton();
    lastQueryIdentity = null;
    boardChanged();
  }

  private OpeningDatabase.Collection pickCollection(String message) {
    if (collections.isEmpty()) return null;
    Object choice =
        JOptionPane.showInputDialog(
            this,
            message,
            text("title"),
            JOptionPane.PLAIN_MESSAGE,
            null,
            collections.stream().map(OpeningDatabase.Collection::name).toArray(),
            collections.get(0).name());
    if (choice == null) return null;
    return collections.stream().filter(c -> c.name().equals(choice)).findFirst().orElse(null);
  }

  private void renameCollection() {
    OpeningDatabase.Collection c = pickCollection(text("renameCollection"));
    if (c == null) return;
    String name = JOptionPane.showInputDialog(this, text("collectionName"), c.name());
    if (name == null || name.isBlank()) return;
    service.renameCollection(c.id(), name, this::refreshCollections);
  }

  private void deleteCollection() {
    OpeningDatabase.Collection c = pickCollection(text("deleteCollection"));
    if (c == null) return;
    int answer =
        JOptionPane.showConfirmDialog(
            this,
            format("deleteConfirm", c.name()),
            text("title"),
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.WARNING_MESSAGE);
    if (answer != JOptionPane.OK_OPTION) return;
    service.deleteCollection(
        c.id(), this::refreshCollections, msg -> status.setText(format("importFailed", msg)));
  }

  private void editFilter() {
    JTextField player = new JTextField(settings.player, 14);
    JTextField rank = new JTextField(settings.rank, 14);
    JTextField event = new JTextField(settings.event, 14);
    JTextField from = new JTextField(settings.minYear > 0 ? "" + settings.minYear : "", 5);
    JTextField to = new JTextField(settings.maxYear > 0 ? "" + settings.maxYear : "", 5);
    JPanel years = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
    years.add(from);
    years.add(new JLabel("–"));
    years.add(to);
    JPanel form = new JPanel(new GridLayout(0, 2, 6, 4));
    form.add(new JLabel(text("filterPlayer")));
    form.add(player);
    form.add(new JLabel(text("filterRank")));
    form.add(rank);
    form.add(new JLabel(text("filterEvent")));
    form.add(event);
    form.add(new JLabel(text("filterYears")));
    form.add(years);
    Object[] options = {text("ok"), text("clearFilter"), text("cancel")};
    int answer =
        JOptionPane.showOptionDialog(
            this,
            form,
            text("filter"),
            JOptionPane.DEFAULT_OPTION,
            JOptionPane.PLAIN_MESSAGE,
            null,
            options,
            options[0]);
    if (answer == 0) {
      settings.player = player.getText().trim();
      settings.rank = rank.getText().trim();
      settings.event = event.getText().trim();
      settings.minYear = parseYear(from.getText());
      settings.maxYear = parseYear(to.getText());
    } else if (answer == 1) {
      settings.player = "";
      settings.rank = "";
      settings.event = "";
      settings.minYear = 0;
      settings.maxYear = 0;
    } else {
      return;
    }
    saveAndRequery();
  }

  private static int parseYear(String s) {
    try {
      int y = Integer.parseInt(s.trim());
      return y >= 1000 && y <= 2999 ? y : 0;
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  private void openFolder() {
    try {
      Desktop.getDesktop().open(service.directory().toFile());
    } catch (Exception e) {
      status.setText(service.directory().toString());
    }
  }

  // --------------------------------------------------------------------------- import

  private void chooseAndImport() {
    if (runningImport != null) return;
    JFileChooser chooser =
        new JFileChooser(settings.lastFolder.isEmpty() ? null : new File(settings.lastFolder));
    chooser.setDialogTitle(text("chooseFiles"));
    chooser.setMultiSelectionEnabled(true);
    chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
    chooser.setFileFilter(new FileNameExtensionFilter("SGF / ZIP", "sgf", "zip"));
    if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
    File[] files = chooser.getSelectedFiles();
    if (files == null || files.length == 0) return;
    if (files[0].getParentFile() != null) {
      settings.lastFolder = files[0].getParentFile().getAbsolutePath();
      settings.save(service.directory());
    }
    JComboBox<String> name = new JComboBox<>();
    name.setEditable(true);
    for (OpeningDatabase.Collection c : collections) name.addItem(c.name());
    String suggested =
        files[0].getParentFile() == null
            ? text("defaultCollection")
            : files[0].getParentFile().getName();
    name.setSelectedItem(collections.isEmpty() ? suggested : collections.get(0).name());
    int answer =
        JOptionPane.showConfirmDialog(
            this,
            new Object[] {text("collectionPrompt"), name},
            text("title"),
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE);
    Object selected = name.getEditor().getItem();
    if (answer != JOptionPane.OK_OPTION || selected == null || selected.toString().isBlank()) {
      return;
    }
    List<Path> paths = new ArrayList<>();
    for (File f : files) paths.add(f.toPath());
    startImport(paths, selected.toString().trim());
  }

  void startImport(List<Path> files, String collectionName) {
    progress.setMinimum(0);
    progress.setMaximum(Math.max(1, files.size()));
    progress.setValue(0);
    progress.setString("");
    progress.setVisible(true);
    cancelImport.setVisible(true);
    status.setText(" ");
    revalidate();
    runningImport =
        service.importFiles(
            files,
            collectionName,
            settings.indexVariations,
            (done, total, games, current) -> {
              progress.setMaximum(Math.max(1, total));
              progress.setValue(done);
              progress.setString(format("importing", done, total, games));
            },
            report -> {
              finishImport();
              String message =
                  format(
                      "importDone", report.gamesAdded(), report.duplicates(), report.failedGames());
              if (report.cancelled()) message = text("importCancelled") + " " + message;
              status.setText(message);
              status.setToolTipText(
                  report.failures().isEmpty() ? null : String.join("\n", report.failures()));
              refreshCollections();
            },
            message -> {
              finishImport();
              status.setText(format("importFailed", message));
              refreshCollections();
            });
  }

  private void finishImport() {
    runningImport = null;
    progress.setVisible(false);
    cancelImport.setVisible(false);
    revalidate();
  }

  // --------------------------------------------------------------------------- text

  private String text(String key) {
    try {
      return bundle.getString("OpeningExplorer." + key);
    } catch (MissingResourceException e) {
      return key;
    }
  }

  private String format(String key, Object... args) {
    return MessageFormat.format(text(key), args);
  }

  private String moveName(int displayPoint, int width) {
    if (displayPoint == GoPosition.PASS) return text("pass");
    return Board.convertCoordinatesToName(displayPoint % width, displayPoint / width);
  }

  // --------------------------------------------------------------------------- table model

  private final class MoveTableModel extends AbstractTableModel {
    private List<OpeningStats.MoveStat> rows = List.of();
    private CanonicalPosition pos;
    private OpeningStats data;
    private boolean raw;

    void set(CanonicalPosition pos, OpeningStats data, boolean raw) {
      boolean structure = this.raw != raw;
      this.pos = pos;
      this.data = data;
      this.raw = raw;
      this.rows = data.moves();
      if (structure) fireTableStructureChanged();
      else fireTableDataChanged();
    }

    OpeningStats.MoveStat moveAt(int row) {
      return rows.get(row);
    }

    @Override
    public int getRowCount() {
      return rows.size();
    }

    @Override
    public int getColumnCount() {
      return raw ? 4 : 3;
    }

    @Override
    public String getColumnName(int column) {
      switch (column) {
        case 0:
          return text("colMove");
        case 1:
          return text("colGames");
        case 2:
          return text("colPercent");
        default:
          return text("colRaw");
      }
    }

    @Override
    public Class<?> getColumnClass(int column) {
      return column == 0 ? String.class : column == 2 ? Double.class : Integer.class;
    }

    @Override
    public Object getValueAt(int row, int column) {
      OpeningStats.MoveStat m = rows.get(row);
      switch (column) {
        case 0:
          return moveName(pos.toDisplay(m.canonicalMove()), pos.width());
        case 1:
          return m.games();
        case 2:
          return data.percent(m);
        default:
          return m.rawOccurrences();
      }
    }
  }

  private static final class PercentRenderer extends DefaultTableCellRenderer {
    PercentRenderer() {
      setHorizontalAlignment(SwingConstants.RIGHT);
    }

    @Override
    protected void setValue(Object value) {
      setText(value instanceof Double d ? String.format(Locale.ROOT, "%.1f%%", d) : "");
    }
  }
}
