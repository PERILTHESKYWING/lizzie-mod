package featurecat.lizzie.gui;

import featurecat.lizzie.explorer.OpeningDatabase;
import java.awt.Component;
import java.awt.FlowLayout;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;

/**
 * The explorer's game filter: drop-downs filled with the players, ranks, events and years found in
 * the selected collections (most common first, with game counts). A value can also be typed; it
 * then matches any game whose field contains it.
 */
final class OpeningFilterDialog {
  /** The chosen filter values; empty strings and zero years mean "any". */
  record Choice(String player, String rank, String event, int minYear, int maxYear) {}

  private static final Pattern COUNT_SUFFIX = Pattern.compile("^(.*?)\\s+\\(\\d+\\)$");

  final JComboBox<String> player;
  final JComboBox<String> rank;
  final JComboBox<String> event;
  final JComboBox<String> fromYear;
  final JComboBox<String> toYear;
  private final JPanel form;

  OpeningFilterDialog(
      OpeningDatabase.Facets facets, Choice current, Function<String, String> text) {
    player = combo(facets.players(), current.player());
    rank = combo(facets.ranks(), current.rank());
    event = combo(facets.events(), current.event());
    Map<String, Integer> years = new java.util.LinkedHashMap<>();
    facets.years().forEach((y, n) -> years.put(String.valueOf(y), n));
    fromYear = combo(years, current.minYear() > 0 ? String.valueOf(current.minYear()) : "");
    toYear = combo(years, current.maxYear() > 0 ? String.valueOf(current.maxYear()) : "");
    for (JComboBox<String> y : List.of(fromYear, toYear)) {
      y.setPreferredSize(new java.awt.Dimension(132, y.getPreferredSize().height));
    }
    JPanel yearRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    yearRow.add(fromYear);
    yearRow.add(new JLabel("–"));
    yearRow.add(toYear);
    form = new JPanel();
    form.setLayout(new javax.swing.BoxLayout(form, javax.swing.BoxLayout.Y_AXIS));
    form.add(row(text.apply("filterPlayer"), player));
    form.add(row(text.apply("filterRank"), rank));
    form.add(row(text.apply("filterEvent"), event));
    form.add(row(text.apply("filterYears"), yearRow));
    JLabel scope =
        new JLabel(java.text.MessageFormat.format(text.apply("filterScope"), facets.games()));
    scope.setBorder(javax.swing.BorderFactory.createEmptyBorder(6, 86, 0, 0));
    JPanel scopeRow = new JPanel(new java.awt.BorderLayout());
    scopeRow.add(scope, java.awt.BorderLayout.WEST);
    form.add(scopeRow);
  }

  private static JPanel row(String label, Component field) {
    JPanel row = new JPanel(new java.awt.BorderLayout(8, 0));
    JLabel l = new JLabel(label);
    l.setPreferredSize(new java.awt.Dimension(78, l.getPreferredSize().height));
    row.add(l, java.awt.BorderLayout.WEST);
    row.add(field, java.awt.BorderLayout.CENTER);
    row.setBorder(javax.swing.BorderFactory.createEmptyBorder(3, 0, 3, 0));
    row.setMaximumSize(new java.awt.Dimension(420, 34));
    return row;
  }

  private static JComboBox<String> combo(Map<String, Integer> values, String selected) {
    JComboBox<String> box = new JComboBox<>();
    box.setEditable(true);
    box.addItem("");
    values.forEach((v, n) -> box.addItem(v + "  (" + n + ")"));
    box.setMaximumRowCount(16);
    box.setPreferredSize(new java.awt.Dimension(300, box.getPreferredSize().height));
    box.setSelectedItem(selected == null ? "" : selected);
    return box;
  }

  /** The value without its " (count)" suffix. */
  static String valueOf(JComboBox<String> box) {
    Object item = box.isEditable() ? box.getEditor().getItem() : box.getSelectedItem();
    String v = item == null ? "" : item.toString().trim();
    Matcher m = COUNT_SUFFIX.matcher(v);
    return m.matches() ? m.group(1).trim() : v;
  }

  static int yearOf(JComboBox<String> box) {
    try {
      int y = Integer.parseInt(valueOf(box));
      return y >= 1000 && y <= 2999 ? y : 0;
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  Choice read() {
    return new Choice(
        valueOf(player), valueOf(rank), valueOf(event), yearOf(fromYear), yearOf(toYear));
  }

  /** Shows the dialog: the new choice, {@code Choice("","","",0,0)} for "clear", or null. */
  Choice show(Component parent, String title, String ok, String clear, String cancel) {
    Object[] options = {ok, clear, cancel};
    int answer =
        JOptionPane.showOptionDialog(
            parent,
            form,
            title,
            JOptionPane.DEFAULT_OPTION,
            JOptionPane.PLAIN_MESSAGE,
            null,
            options,
            options[0]);
    if (answer == 0) return read();
    if (answer == 1) return new Choice("", "", "", 0, 0);
    return null;
  }
}
