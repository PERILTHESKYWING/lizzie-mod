package featurecat.lizzie.gui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.List;

/**
 * Popular professional moves drawn on the main board by the Opening Explorer: a small ring with the
 * share of games on each point, and a stronger ring on the move selected in the panel. The panel
 * replaces the whole list on the event thread; the board only reads it while painting.
 */
final class OpeningExplorerMarks {
  /** One marked point in display coordinates; {@code label} is short text such as "42%". */
  record Mark(int x, int y, String label, boolean selected) {}

  private volatile Color ring = new Color(52, 120, 220, 210);
  private volatile Color fill = new Color(52, 120, 220, 60);
  private volatile Color selectedRing = new Color(235, 120, 30, 235);
  private volatile Color selectedFill = new Color(235, 120, 30, 80);
  private static final Color TEXT_BG = new Color(255, 255, 255, 215);
  private static final Color TEXT = new Color(20, 20, 20);

  private volatile List<Mark> marks = List.of();

  void set(List<Mark> value) {
    marks = List.copyOf(value);
  }

  /** Colours for ordinary and selected markers (alpha is applied here). */
  void setColors(Color mark, Color selected) {
    ring = withAlpha(mark, 210);
    fill = withAlpha(mark, 60);
    selectedRing = withAlpha(selected, 235);
    selectedFill = withAlpha(selected, 80);
  }

  private static Color withAlpha(Color c, int alpha) {
    return new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha);
  }

  void clear() {
    marks = List.of();
  }

  List<Mark> marks() {
    return marks;
  }

  /**
   * @param left the screen x of the board's first line
   * @param top the screen y of the board's first line
   */
  void paint(Graphics2D graphics, int left, int top, int squareWidth, int squareHeight) {
    List<Mark> current = marks;
    if (current.isEmpty() || squareWidth <= 0 || squareHeight <= 0) return;
    Graphics2D g = (Graphics2D) graphics.create();
    try {
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g.setRenderingHint(
          RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
      int radius = Math.max(3, squareWidth * 40 / 100);
      Font font = g.getFont().deriveFont(Font.BOLD, Math.max(8f, squareWidth * 0.34f));
      g.setFont(font);
      FontMetrics fm = g.getFontMetrics();
      for (Mark m : current) {
        int cx = left + m.x() * squareWidth;
        int cy = top + m.y() * squareHeight;
        g.setColor(m.selected() ? selectedFill : fill);
        g.fillOval(cx - radius, cy - radius, radius * 2, radius * 2);
        g.setColor(m.selected() ? selectedRing : ring);
        g.setStroke(new BasicStroke(Math.max(1.5f, squareWidth / (m.selected() ? 8f : 14f))));
        g.drawOval(cx - radius, cy - radius, radius * 2, radius * 2);
        if (m.label() != null && !m.label().isEmpty()) {
          int w = fm.stringWidth(m.label());
          int h = fm.getAscent();
          g.setColor(TEXT_BG);
          g.fillRoundRect(cx - w / 2 - 2, cy - h / 2 - 1, w + 4, h + 2, 4, 4);
          g.setColor(TEXT);
          g.drawString(m.label(), cx - w / 2, cy + h / 2 - 1);
        }
      }
    } finally {
      g.dispose();
    }
  }
}
