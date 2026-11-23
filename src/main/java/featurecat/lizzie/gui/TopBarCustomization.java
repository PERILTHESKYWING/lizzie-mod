package featurecat.lizzie.gui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.GridLayout;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.AbstractButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Which items of the top toolbar are shown. Items are identified by their visible text or tooltip
 * (for example "Komi", "Rules", "Fox Kifu"); the unticked ones are hidden every time the toolbar is
 * laid out, so the choice survives the toolbar being rebuilt. Separators with nothing visible
 * between them are hidden too. The choice is kept in {@code top-toolbar.json} in the work folder.
 */
final class TopBarCustomization {
  private static final Logger LOGGER = LoggerFactory.getLogger(TopBarCustomization.class);
  static final String FILE = "top-toolbar.json";

  private final Path file;
  private final Set<String> hidden = new LinkedHashSet<>();

  /** Components this class hid, so un-ticking can show exactly those again. */
  private final Map<Component, Boolean> hiddenByUs = new IdentityHashMap<>();

  TopBarCustomization(Path file) {
    this.file = file;
    load();
  }

  Set<String> hiddenLabels() {
    return hidden;
  }

  void setHidden(Set<String> labels) {
    hidden.clear();
    hidden.addAll(labels);
    save();
  }

  /** The text that names a toolbar item, or "" when it has none (it then stays visible). */
  static String labelOf(Component c) {
    String text = ownText(c);
    if (!text.isEmpty()) return text;
    if (c instanceof Container container) {
      for (Component child : container.getComponents()) {
        String t = ownText(child);
        if (!t.isEmpty()) return t;
      }
    }
    if (c instanceof JComponent j && j.getAccessibleContext() != null) {
      String name = j.getAccessibleContext().getAccessibleName();
      if (name != null && !name.isBlank()) return clean(name);
    }
    return "";
  }

  private static String ownText(Component c) {
    String t = "";
    if (c instanceof AbstractButton b) t = b.getText();
    else if (c instanceof JLabel l) t = l.getText();
    if ((t == null || t.isBlank()) && c instanceof JComponent j && !(c instanceof JTextField)) {
      t = j.getToolTipText();
    }
    return clean(t);
  }

  private static String clean(String s) {
    if (s == null) return "";
    String t = s.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim();
    t = t.replaceAll("[:：]$", "").trim();
    return t.length() > 40 ? t.substring(0, 40) : t;
  }

  static boolean isSeparator(Component c) {
    return c.getClass().getSimpleName().equals("ToolbarSeparator");
  }

  /** The toolbar items in order, flattening group panels. */
  static List<Component> items(Container content) {
    List<Component> out = new ArrayList<>();
    for (Component c : content.getComponents()) {
      if (c.getClass().getSimpleName().equals("GroupPanel")) {
        for (Component child : ((Container) c).getComponents()) out.add(child);
      } else {
        out.add(c);
      }
    }
    return out;
  }

  /** Hides the unticked items and the separators they leave stranded. Call before layout. */
  void apply(Container content) {
    List<Component> items = items(content);
    for (Component c : items) {
      if (isSeparator(c)) continue;
      String label = labelOf(c);
      boolean hide = !label.isEmpty() && hidden.contains(label);
      if (hide && c.isVisible()) {
        c.setVisible(false);
        hiddenByUs.put(c, Boolean.TRUE);
      } else if (!hide && hiddenByUs.remove(c) != null) {
        c.setVisible(true);
      }
    }
    // A separator is shown only between two visible items.
    boolean seenItem = false;
    Component pending = null;
    for (Component c : items) {
      if (isSeparator(c)) {
        if (pending != null) hideSeparator(pending);
        pending = seenItem ? c : null;
        if (!seenItem) hideSeparator(c);
        continue;
      }
      if (!c.isVisible()) continue;
      if (pending != null) {
        showSeparator(pending);
        pending = null;
      }
      seenItem = true;
    }
    if (pending != null) hideSeparator(pending);
  }

  private void hideSeparator(Component c) {
    if (c.isVisible()) {
      c.setVisible(false);
      hiddenByUs.put(c, Boolean.TRUE);
    }
  }

  private void showSeparator(Component c) {
    if (hiddenByUs.remove(c) != null) c.setVisible(true);
  }

  /** Labels of the items that can be chosen, in toolbar order. */
  List<String> choosableLabels(Container content) {
    Set<String> labels = new LinkedHashSet<>();
    for (Component c : items(content)) {
      if (isSeparator(c)) continue;
      String label = labelOf(c);
      if (!label.isEmpty()) labels.add(label);
    }
    labels.addAll(hidden); // items not built right now stay in the list
    return new ArrayList<>(labels);
  }

  /** The checklist dialog; applies and saves the choice on OK. */
  boolean showDialog(Component parent, Container content, Map<String, String> text) {
    List<String> labels = choosableLabels(content);
    JPanel list = new JPanel(new GridLayout(0, 2, 12, 2));
    List<JCheckBox> boxes = new ArrayList<>();
    for (String label : labels) {
      JCheckBox box = new JCheckBox(label, !hidden.contains(label));
      boxes.add(box);
      list.add(box);
    }
    JPanel quick = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
    javax.swing.JButton all = new javax.swing.JButton(text.get("showAll"));
    all.addActionListener(e -> boxes.forEach(b -> b.setSelected(true)));
    javax.swing.JButton none = new javax.swing.JButton(text.get("hideAll"));
    none.addActionListener(e -> boxes.forEach(b -> b.setSelected(false)));
    quick.add(all);
    quick.add(none);
    JPanel panel = new JPanel(new BorderLayout(0, 8));
    panel.add(new JLabel(text.get("hint")), BorderLayout.NORTH);
    JScrollPane scroll = new JScrollPane(list);
    scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
    scroll.setPreferredSize(
        new java.awt.Dimension(640, Math.min(420, 30 + 26 * (labels.size() + 1) / 2)));
    panel.add(scroll, BorderLayout.CENTER);
    panel.add(quick, BorderLayout.SOUTH);
    int answer =
        JOptionPane.showConfirmDialog(
            parent,
            panel,
            text.get("title"),
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE);
    if (answer != JOptionPane.OK_OPTION) return false;
    Set<String> next = new LinkedHashSet<>();
    for (JCheckBox b : boxes) if (!b.isSelected()) next.add(b.getText());
    setHidden(next);
    return true;
  }

  private void load() {
    if (file == null || !Files.isRegularFile(file)) return;
    try {
      JSONArray a =
          new JSONObject(Files.readString(file, StandardCharsets.UTF_8)).optJSONArray("hidden");
      if (a != null) for (int i = 0; i < a.length(); i++) hidden.add(a.optString(i));
    } catch (IOException | RuntimeException e) {
      LOGGER.warn("Ignoring unreadable {}", file, e);
    }
  }

  private void save() {
    if (file == null) return;
    try {
      Files.createDirectories(file.getParent());
      Files.writeString(
          file,
          new JSONObject().put("hidden", new JSONArray(hidden)).toString(2),
          StandardCharsets.UTF_8);
    } catch (IOException e) {
      LOGGER.warn("Could not save {}", file, e);
    }
  }
}
