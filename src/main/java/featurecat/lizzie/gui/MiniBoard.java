package featurecat.lizzie.gui;

import featurecat.lizzie.explorer.GameReplay;
import featurecat.lizzie.explorer.GoPosition;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.JComponent;

/** A small read-only board: a game at one position, with the move played next ringed. */
final class MiniBoard extends JComponent {
  private static final Color WOOD = new Color(0xDC, 0xB3, 0x6B);
  private static final Color LINE = new Color(0x4A, 0x37, 0x1C);
  private static final Color NEXT = new Color(0xEB, 0x7A, 0x1E);

  private GameReplay.Snapshot snapshot;
  private String caption = "";

  MiniBoard(int size) {
    setPreferredSize(new Dimension(size, size + 20));
    setOpaque(true);
  }

  void show(GameReplay.Snapshot snapshot, String caption) {
    this.snapshot = snapshot;
    this.caption = caption == null ? "" : caption;
    repaint();
  }

  @Override
  protected void paintComponent(Graphics graphics) {
    Graphics2D g = (Graphics2D) graphics.create();
    try {
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g.setColor(ExplorerTheme.SURFACE);
      g.fillRect(0, 0, getWidth(), getHeight());
      int boardSide = Math.min(getWidth(), getHeight() - 20);
      g.setColor(WOOD);
      g.fillRoundRect(0, 0, boardSide, boardSide, 6, 6);
      if (snapshot == null) return;
      int w = snapshot.width();
      int h = snapshot.height();
      double step = (boardSide - 12.0) / Math.max(w, h);
      double ox = 6 + step / 2;
      double oy = 6 + step / 2;
      g.setColor(LINE);
      g.setStroke(new BasicStroke(1f));
      for (int x = 0; x < w; x++) {
        int px = (int) Math.round(ox + x * step);
        g.drawLine(px, (int) Math.round(oy), px, (int) Math.round(oy + (h - 1) * step));
      }
      for (int y = 0; y < h; y++) {
        int py = (int) Math.round(oy + y * step);
        g.drawLine((int) Math.round(ox), py, (int) Math.round(ox + (w - 1) * step), py);
      }
      int r = Math.max(2, (int) Math.round(step * 0.46));
      for (int i = 0; i < snapshot.cells().length; i++) {
        byte c = snapshot.cells()[i];
        if (c == GoPosition.EMPTY) continue;
        int cx = (int) Math.round(ox + (i % w) * step);
        int cy = (int) Math.round(oy + (i / w) * step);
        g.setColor(
            c == GoPosition.BLACK ? new Color(0x18, 0x18, 0x18) : new Color(0xF4, 0xF4, 0xF0));
        g.fillOval(cx - r, cy - r, 2 * r, 2 * r);
        g.setColor(new Color(0, 0, 0, 120));
        g.drawOval(cx - r, cy - r, 2 * r, 2 * r);
      }
      int next = snapshot.nextMove();
      if (next >= 0) {
        int cx = (int) Math.round(ox + (next % w) * step);
        int cy = (int) Math.round(oy + (next / w) * step);
        g.setColor(NEXT);
        g.setStroke(new BasicStroke(Math.max(2f, (float) step / 6f)));
        g.drawOval(cx - r, cy - r, 2 * r, 2 * r);
      }
      g.setColor(ExplorerTheme.TEXT);
      g.setFont(getFont().deriveFont(Font.PLAIN, 12f));
      g.drawString(caption, 2, boardSide + 15);
    } finally {
      g.dispose();
    }
  }
}
