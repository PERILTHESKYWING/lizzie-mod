package featurecat.lizzie.gui;

import featurecat.lizzie.explorer.BoardPositionAdapter;
import featurecat.lizzie.explorer.CanonicalPosition;
import featurecat.lizzie.explorer.GoPosition;
import featurecat.lizzie.explorer.OpeningDatabase;
import featurecat.lizzie.explorer.OpeningExplorerService;
import featurecat.lizzie.explorer.OpeningExplorerSettings;
import featurecat.lizzie.explorer.OpeningFilter;
import featurecat.lizzie.explorer.OpeningImporter;
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
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;

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
  private static final int MAX_LISTED_GAMES = 1000;
  private static final Color BACKGROUND = ExplorerTheme.SURFACE;
  private static final Color LINE = ExplorerTheme.BORDER;
  private static final Color MUTED = ExplorerTheme.MUTED;

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
  private final MoveListModel model = new MoveListModel();
  private final javax.swing.JList<OpeningExplorerMoveRow.Row> table =
      new javax.swing.JList<>(model);

  private List<OpeningDatabase.Collection> collections = List.of();
  private CanonicalPosition position;
  private OpeningStats stats;
  private String lastQueryIdentity;
  private int dataVersion;
  private OpeningExplorerService.ImportHandle runningImport;
  private OpeningGamesWindow lastGamesWindow;
  private final OpeningExplorerMoveRow rowRenderer = new OpeningExplorerMoveRow();

  /** Fox access; replaced in tests. */
  OpeningImporter.FoxSource foxSource = new FoxClientSource();

  static final int FOX_GAMES_PER_ACCOUNT = 200;

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
    applyColors();
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
    ExplorerTheme.label(title, false, title.getFont().getSize2D() + 1f, Font.BOLD);
    titleRow.add(title, BorderLayout.CENTER);
    JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
    buttons.setOpaque(false);
    JButton importButton = smallButton("＋ " + text("import"));
    importButton.setToolTipText(text("import"));
    importButton.addActionListener(e -> showImportMenu(importButton));
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
    collectionsButton.setBackground(ExplorerTheme.HEADER);
    collectionsButton.setForeground(ExplorerTheme.TEXT);
    collectionsButton.setOpaque(true);
    collectionsButton.setContentAreaFilled(true);
    collectionsButton.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(ExplorerTheme.BORDER),
            BorderFactory.createEmptyBorder(4, 8, 4, 8)));
    collectionsButton.setMargin(new java.awt.Insets(2, 6, 2, 6));
    collectionsButton.addActionListener(e -> showCollectionsMenu());
    collectionsButton.setAlignmentX(Component.LEFT_ALIGNMENT);
    collectionsButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
    header.add(collectionsButton);
    header.add(Box.createVerticalStrut(6));
    summary.setAlignmentX(Component.LEFT_ALIGNMENT);
    ExplorerTheme.label(summary, false, summary.getFont().getSize2D(), Font.PLAIN);
    header.add(summary);
    return header;
  }

  private JScrollPane buildTable() {
    table.setCellRenderer(rowRenderer);
    table.setFixedCellHeight(OpeningExplorerMoveRow.HEIGHT);
    // Keys stay with the board (arrows navigate the game, K closes the panel).
    table.setFocusable(false);
    table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    table.addListSelectionListener(
        e -> {
          if (!e.getValueIsAdjusting()) updateMarks();
        });
    table.setToolTipText(text("gamesHint"));
    table.addMouseListener(
        new java.awt.event.MouseAdapter() {
          @Override
          public void mouseClicked(java.awt.event.MouseEvent e) {
            int row = table.locationToIndex(e.getPoint());
            boolean inside = row >= 0 && table.getCellBounds(row, row).contains(e.getPoint());
            if (e.getClickCount() == 2 && inside) showGames(row);
          }
        });
    JScrollPane scroll = new JScrollPane(table);
    scroll.setBorder(BorderFactory.createMatteBorder(1, 0, 1, 0, LINE));
    table.setBackground(ExplorerTheme.SURFACE);
    scroll.getViewport().setBackground(ExplorerTheme.SURFACE);
    scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
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
    return ExplorerTheme.flat(new JButton(label));
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
    int total = totalGames();
    if (total == 0) {
      summary.setText(text("emptyDatabase"));
    } else if (stats.games() == 0) {
      summary.setText("<html>" + text("noGames") + "</html>");
      findLastMatch();
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
    table.clearSelection();
    for (int row = 0; row < model.getSize(); row++) {
      if (model.moveAt(row).canonicalMove() == selectedMove) {
        table.setSelectedIndex(row);
        break;
      }
    }
    updateMarks();
  }

  /** Walks back through the game to the last position the collection still had games for. */
  private void findLastMatch() {
    BoardHistoryList h = history.get();
    if (h == null || h.getCurrentHistoryNode() == null) return;
    List<featurecat.lizzie.explorer.PositionKey> keys = new ArrayList<>();
    List<Integer> moveNumbers = new ArrayList<>();
    BoardHistoryNode node = h.getCurrentHistoryNode();
    for (int i = 0; i < 400 && node.previous().isPresent(); i++) {
      node = node.previous().get();
      BoardData d = node.getData();
      if (d == null || d.stones == null) break;
      Stone[] parent = node.previous().map(p -> p.getData().stones).orElse(null);
      keys.add(
          BoardPositionAdapter.canonical(
                  Board.boardWidth, Board.boardHeight, d.stones, parent, d.lastMove, d.blackToPlay)
              .key());
      moveNumbers.add(d.moveNumber);
    }
    if (keys.isEmpty()) return;
    String identity = lastQueryIdentity;
    service.lastMatch(
        keys,
        settings.filter(),
        settings.includeVariations,
        found -> {
          if (!java.util.Objects.equals(identity, lastQueryIdentity) || found[0] < 0) return;
          summary.setText(
              "<html>"
                  + text("noGames")
                  + "<br><span style='color:#B7BCC4'>"
                  + format("lastMatch", moveNumbers.get(found[0]), found[1])
                  + "</span></html>");
        });
  }

  private int selectedCanonicalMove() {
    int row = table.getSelectedIndex();
    return row >= 0 && row < model.getSize() ? model.moveAt(row).canonicalMove() : -100;
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

  /** Lists the games behind one row (double-click): hover to preview, double-click to open. */
  void showGames(int row) {
    if (position == null || stats == null || row < 0 || row >= model.getSize()) return;
    OpeningStats.MoveStat move = model.moveAt(row);
    String name = moveName(position.toDisplay(move.canonicalMove()), position.width());
    featurecat.lizzie.explorer.PositionKey key = position.key();
    service.gamesPlaying(
        key,
        move.canonicalMove(),
        settings.filter(),
        settings.includeVariations,
        MAX_LISTED_GAMES,
        games ->
            lastGamesWindow =
                new OpeningGamesWindow(
                    SwingUtilities.getWindowAncestor(this),
                    format("gamesTitle", name, games.size()),
                    games,
                    key,
                    service::record,
                    this::openGame,
                    this::text));
  }

  /** Opens a game from the games list in the main window at the explorer's position. */
  private void openGame(String record, int movesPlayed) {
    if (SwingUtilities.getWindowAncestor(this) instanceof LizzieFrame frame) {
      frame.openExplorerGame(record, movesPlayed);
    }
  }

  /** The games window opened last (for tests). */
  OpeningGamesWindow lastGamesWindow() {
    return lastGamesWindow;
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
    JMenuItem colors = new JMenuItem(text("colors"));
    colors.addActionListener(e -> editColors());
    menu.add(colors);
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
    service.facets(
        settings.collections,
        facets -> {
          OpeningFilterDialog dialog =
              new OpeningFilterDialog(
                  facets,
                  new OpeningFilterDialog.Choice(
                      settings.player,
                      settings.rank,
                      settings.event,
                      settings.minYear,
                      settings.maxYear),
                  this::text);
          OpeningFilterDialog.Choice choice =
              dialog.show(this, text("filter"), text("ok"), text("clearFilter"), text("cancel"));
          if (choice == null) return;
          settings.player = choice.player();
          settings.rank = choice.rank();
          settings.event = choice.event();
          settings.minYear = choice.minYear();
          settings.maxYear = choice.maxYear();
          saveAndRequery();
        });
  }

  private void openFolder() {
    try {
      Desktop.getDesktop().open(service.directory().toFile());
    } catch (Exception e) {
      status.setText(service.directory().toString());
    }
  }

  // --------------------------------------------------------------------------- import

  private void showImportMenu(Component anchor) {
    JPopupMenu menu = new JPopupMenu();
    JMenuItem files = new JMenuItem(text("importFiles"));
    files.addActionListener(e -> chooseAndImport());
    menu.add(files);
    JMenuItem fox = new JMenuItem(text("importFox"));
    fox.addActionListener(e -> chooseFoxImport());
    menu.add(fox);
    menu.show(anchor, 0, anchor.getHeight());
  }

  private JComboBox<String> collectionChooser(String suggested) {
    JComboBox<String> name = new JComboBox<>();
    name.setEditable(true);
    for (OpeningDatabase.Collection c : collections) name.addItem(c.name());
    name.setSelectedItem(suggested);
    return name;
  }

  private void chooseFoxImport() {
    if (runningImport != null) return;
    JTextField accounts = new JTextField(settings.lastFoxAccounts, 22);
    JComboBox<String> name = collectionChooser(text("foxCollection"));
    int answer =
        JOptionPane.showConfirmDialog(
            this,
            new Object[] {
              text("foxAccounts"),
              accounts,
              format("foxLimit", FOX_GAMES_PER_ACCOUNT),
              text("collectionPrompt"),
              name
            },
            text("importFox"),
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE);
    Object selected = name.getEditor().getItem();
    List<String> names = new ArrayList<>();
    for (String part : accounts.getText().split("[,;，、\\s]+")) {
      if (!part.isBlank()) names.add(part.trim());
    }
    if (answer != JOptionPane.OK_OPTION || names.isEmpty() || selected == null) return;
    if (selected.toString().isBlank()) return;
    settings.lastFoxAccounts = String.join(", ", names);
    settings.save(service.directory());
    startFoxImport(names, selected.toString().trim());
  }

  void startFoxImport(List<String> accounts, String collectionName) {
    beginImportProgress(accounts.size() * FOX_GAMES_PER_ACCOUNT);
    runningImport =
        service.importFox(
            accounts,
            collectionName,
            settings.indexVariations,
            FOX_GAMES_PER_ACCOUNT,
            foxSource,
            (done, total, games, account) -> {
              progress.setMaximum(Math.max(1, total));
              progress.setValue(done);
              progress.setString(format("foxImporting", account, done, total, games));
            },
            this::onImportDone,
            this::onImportFailed);
  }

  private void beginImportProgress(int max) {
    progress.setMinimum(0);
    progress.setMaximum(Math.max(1, max));
    progress.setValue(0);
    progress.setString("");
    progress.setVisible(true);
    cancelImport.setVisible(true);
    status.setText(" ");
    revalidate();
  }

  private void onImportDone(OpeningImporter.Report report) {
    finishImport();
    String message =
        format("importDone", report.gamesAdded(), report.duplicates(), report.failedGames());
    if (report.cancelled()) message = text("importCancelled") + " " + message;
    status.setText(message);
    status.setToolTipText(
        report.failures().isEmpty() ? null : String.join("\n", report.failures()));
    refreshCollections();
  }

  private void onImportFailed(String message) {
    finishImport();
    status.setText(format("importFailed", message));
    refreshCollections();
  }

  /** The application's Fox client behind the importer's {@link OpeningImporter.FoxSource}. */
  private static final class FoxClientSource implements OpeningImporter.FoxSource {
    private featurecat.lizzie.analysis.GetFoxRequest client;

    private synchronized featurecat.lizzie.analysis.GetFoxRequest client() {
      if (client == null) client = new featurecat.lizzie.analysis.GetFoxRequest(null);
      return client;
    }

    @Override
    public String findUid(String accountName) throws java.io.IOException {
      try {
        return client().findUid(accountName);
      } catch (RuntimeException e) {
        throw new java.io.IOException(e.getMessage(), e);
      }
    }

    @Override
    public List<String> gameIds(String uid, String after) throws java.io.IOException {
      try {
        return client().gameIds(uid, after);
      } catch (RuntimeException e) {
        throw new java.io.IOException(e.getMessage(), e);
      }
    }

    @Override
    public String gameSgf(String gameId) throws java.io.IOException {
      try {
        String sgf = client().gameSgf(gameId);
        if (sgf.isBlank()) throw new java.io.IOException("Fox returned no game record");
        return sgf;
      } catch (RuntimeException e) {
        throw new java.io.IOException(e.getMessage(), e);
      }
    }
  }

  /** Applies the colour settings to the board markers and the bars. */
  private void applyColors() {
    java.awt.Color mark =
        OpeningExplorerSettings.color(settings.markColor, OpeningExplorerSettings.DEFAULT_MARK);
    java.awt.Color selected =
        OpeningExplorerSettings.color(
            settings.selectedColor, OpeningExplorerSettings.DEFAULT_SELECTED);
    java.awt.Color bar =
        OpeningExplorerSettings.color(settings.barColor, OpeningExplorerSettings.DEFAULT_BAR);
    marks.setColors(mark, selected);
    rowRenderer.setColors(bar, selected);
    table.repaint();
  }

  private void editColors() {
    java.awt.Color[] chosen = {
      OpeningExplorerSettings.color(settings.markColor, OpeningExplorerSettings.DEFAULT_MARK),
      OpeningExplorerSettings.color(settings.barColor, OpeningExplorerSettings.DEFAULT_BAR),
      OpeningExplorerSettings.color(
          settings.selectedColor, OpeningExplorerSettings.DEFAULT_SELECTED)
    };
    String[] labels = {text("colorMarks"), text("colorBars"), text("colorSelected")};
    JPanel form = new JPanel(new GridLayout(0, 2, 8, 6));
    JButton[] swatches = new JButton[3];
    for (int i = 0; i < 3; i++) {
      int index = i;
      JButton swatch = new JButton("      ");
      swatch.setBackground(chosen[i]);
      swatch.setOpaque(true);
      swatch.setBorderPainted(true);
      swatch.addActionListener(
          e -> {
            java.awt.Color c =
                ColorWheel.showDialog(
                    this, labels[index], chosen[index], text("ok"), text("cancel"));
            if (c != null) {
              chosen[index] = c;
              swatch.setBackground(c);
            }
          });
      swatches[i] = swatch;
      form.add(new JLabel(labels[i]));
      form.add(swatch);
    }
    JButton katago = new JButton(text("colorKataGo"));
    katago.addActionListener(
        e -> {
          if (SwingUtilities.getWindowAncestor(this) instanceof LizzieFrame frame) {
            frame.openConfigDialog2AtSetting("config.theme.best-move-color");
          }
        });
    Object[] options = {text("ok"), text("colorReset"), text("cancel")};
    int answer =
        JOptionPane.showOptionDialog(
            this,
            new Object[] {form, katago},
            text("colors"),
            JOptionPane.DEFAULT_OPTION,
            JOptionPane.PLAIN_MESSAGE,
            null,
            options,
            options[0]);
    if (answer == 0) {
      settings.markColor = OpeningExplorerSettings.hex(chosen[0]);
      settings.barColor = OpeningExplorerSettings.hex(chosen[1]);
      settings.selectedColor = OpeningExplorerSettings.hex(chosen[2]);
    } else if (answer == 1) {
      settings.markColor = OpeningExplorerSettings.DEFAULT_MARK;
      settings.barColor = OpeningExplorerSettings.DEFAULT_BAR;
      settings.selectedColor = OpeningExplorerSettings.DEFAULT_SELECTED;
    } else {
      return;
    }
    settings.save(service.directory());
    applyColors();
    updateMarks();
  }

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
    String suggested =
        files[0].getParentFile() == null
            ? text("defaultCollection")
            : files[0].getParentFile().getName();
    JComboBox<String> name =
        collectionChooser(collections.isEmpty() ? suggested : collections.get(0).name());
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
    beginImportProgress(files.size());
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
            this::onImportDone,
            this::onImportFailed);
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

  // --------------------------------------------------------------------------- list model

  /** Number of result rows (for tests). */
  int rowCount() {
    return model.getSize();
  }

  /** The row shown at {@code index} (for tests). */
  OpeningExplorerMoveRow.Row rowAt(int index) {
    return model.getElementAt(index);
  }

  void selectRow(int index) {
    table.setSelectedIndex(index);
  }

  private final class MoveListModel
      extends javax.swing.AbstractListModel<OpeningExplorerMoveRow.Row> {
    private List<OpeningStats.MoveStat> moves = List.of();
    private List<OpeningExplorerMoveRow.Row> rows = List.of();

    void set(CanonicalPosition pos, OpeningStats data, boolean raw) {
      int old = rows.size();
      moves = data.moves();
      List<OpeningExplorerMoveRow.Row> next = new ArrayList<>(moves.size());
      for (int i = 0; i < moves.size(); i++) {
        OpeningStats.MoveStat m = moves.get(i);
        String detail = format("gamesCount", String.format(Locale.ROOT, "%,d", m.games()));
        if (raw) detail += " \u00B7 " + format("rawCount", m.rawOccurrences());
        next.add(
            new OpeningExplorerMoveRow.Row(
                moveName(pos.toDisplay(m.canonicalMove()), pos.width()),
                data.percent(m),
                detail,
                i == 0));
      }
      rows = next;
      if (old > 0) fireIntervalRemoved(this, 0, old - 1);
      if (!rows.isEmpty()) fireIntervalAdded(this, 0, rows.size() - 1);
    }

    OpeningStats.MoveStat moveAt(int row) {
      return moves.get(row);
    }

    @Override
    public int getSize() {
      return rows.size();
    }

    @Override
    public OpeningExplorerMoveRow.Row getElementAt(int index) {
      return rows.get(index);
    }
  }
}
