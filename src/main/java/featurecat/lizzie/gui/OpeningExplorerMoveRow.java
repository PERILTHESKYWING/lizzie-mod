package featurecat.lizzie.gui;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.Locale;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.ListCellRenderer;

/**
 * One Opening Explorer result drawn as a bar: the move, a bar whose length is the share of games
 * that chose it, the percentage, and a small line with the game count underneath. The most popular
 * move is drawn darker; the selected move uses the board marker's orange.
 */
final class OpeningExplorerMoveRow extends JComponent
    implements ListCellRenderer<OpeningExplorerMoveRow.Row> {
  /** What a row shows; {@code detail} is the small grey line ("174 games"). */
  record Row(String move, double percent, String detail, boolean top) {}

  static final int HEIGHT = 36;
  private static final Color TRACK = new Color(0xe8, 0xeb, 0xf0);
  private static final Color TOP_BAR = new Color(0x2f, 0x6f, 0xd6);
  private static final Color BAR = new Color(0x7d, 0xa6, 0xe6);
  private static final Color SELECTED_BAR = new Color(0xeb, 0x7a, 0x1e);
  private static final Color SELECTED_BG = new Color(0xfd, 0xf1, 0xe6);
  private static final Color TEXT = new Color(0x1e, 0x1e, 0x1e);
  private static final Color MUTED = new Color(0x70, 0x70, 0x70);
  private static final Color SEPARATOR = new Color(0xee, 0xee, 0xec);

  private Row row = new Row("", 0, "", false);
  private boolean selected;

  OpeningExplorerMoveRow() {
    setOpaque(true);
  }

  @Override
  public Component getListCellRendererComponent(
      JList<? extends Row> list, Row value, int index, boolean isSelected, boolean hasFocus) {
    row = value;
    selected = isSelected;
    setFont(list.getFont());
    return this;
  }

  @Override
  public Dimension getPreferredSize() {
    return new Dimension(200, HEIGHT);
  }

  @Override
  protected void paintComponent(Graphics graphics) {
    Graphics2D g = (Graphics2D) graphics.create();
    try {
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g.setRenderingHint(
          RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
      int w = getWidth();
      int h = getHeight();
      g.setColor(selected ? SELECTED_BG : Color.WHITE);
      g.fillRect(0, 0, w, h);
      g.setColor(SEPARATOR);
      g.drawLine(0, h - 1, w, h - 1);

      Font base = getFont();
      Font bold = base.deriveFont(Font.BOLD, base.getSize2D() + 1f);
      Font small = base.deriveFont(Font.PLAIN, Math.max(9f, base.getSize2D() - 2f));
      g.setFont(bold);
      FontMetrics fm = g.getFontMetrics();
      int left = 10;
      int moveWidth = Math.max(fm.stringWidth("Q16"), fm.stringWidth(row.move())) + 8;
      String percent = String.format(Locale.ROOT, "%.1f%%", row.percent());
      int percentWidth = fm.stringWidth("100.0%") + 6;
      int barX = left + moveWidth;
      int barW = Math.max(20, w - barX - percentWidth - 10);
      int barH = 12;
      int barY = 7;
      int baseline = barY + barH / 2 + fm.getAscent() / 2 - 1;

      g.setColor(TEXT);
      g.drawString(row.move(), left, baseline);

      g.setColor(TRACK);
      g.fillRoundRect(barX, barY, barW, barH, barH, barH);
      int fill = (int) Math.round(barW * Math.max(0, Math.min(100, row.percent())) / 100.0);
      if (row.percent() > 0) fill = Math.max(fill, barH / 2);
      g.setColor(selected ? SELECTED_BAR : row.top() ? TOP_BAR : BAR);
      if (fill > 0) g.fillRoundRect(barX, barY, fill, barH, barH, barH);

      g.setColor(TEXT);
      g.drawString(percent, w - 8 - fm.stringWidth(percent), baseline);

      g.setFont(small);
      g.setColor(MUTED);
      g.drawString(row.detail(), barX, barY + barH + g.getFontMetrics().getAscent() + 2);
    } finally {
      g.dispose();
    }
  }
}
