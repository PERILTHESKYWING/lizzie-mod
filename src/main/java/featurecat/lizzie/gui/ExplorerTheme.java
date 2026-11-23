package featurecat.lizzie.gui;

import java.awt.Color;
import java.awt.Font;
import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JTable;

/**
 * The colours of the Opening Explorer and its windows: the same dark surfaces as the sidebar and
 * the candidate table beside it, so the explorer looks like part of the main window.
 */
final class ExplorerTheme {
  static final Color SURFACE = CandidateTableStyle.SURFACE;
  static final Color SURFACE_ALT = CandidateTableStyle.SURFACE_ALT;
  static final Color HEADER = CandidateTableStyle.HEADER;
  static final Color TEXT = CandidateTableStyle.TEXT;
  static final Color MUTED = CandidateTableStyle.HEADER_TEXT;
  static final Color BORDER = CandidateTableStyle.BORDER;
  static final Color TRACK = new Color(0x3A, 0x3E, 0x44);
  static final Color HOVER = new Color(255, 255, 255, 22);
  static final Color SELECTED_ROW = new Color(255, 255, 255, 34);

  private ExplorerTheme() {}

  /** A flat button in the dark header: no box until hovered, light text. */
  static <B extends AbstractButton> B flat(B button) {
    button.setFocusable(false);
    button.setContentAreaFilled(false);
    button.setBorderPainted(false);
    button.setOpaque(false);
    button.setForeground(TEXT);
    button.setBorder(BorderFactory.createEmptyBorder(3, 7, 3, 7));
    button.addMouseListener(
        new java.awt.event.MouseAdapter() {
          @Override
          public void mouseEntered(java.awt.event.MouseEvent e) {
            button.setContentAreaFilled(true);
            button.setBackground(new Color(0x3A, 0x3E, 0x44));
          }

          @Override
          public void mouseExited(java.awt.event.MouseEvent e) {
            button.setContentAreaFilled(false);
          }
        });
    return button;
  }

  static void label(JComponent c, boolean muted, float size, int style) {
    c.setForeground(muted ? MUTED : TEXT);
    c.setFont(c.getFont().deriveFont(style, size));
  }

  /** Dark table: surfaces, header and selection in the explorer colours. */
  static void table(JTable t) {
    t.setBackground(SURFACE);
    t.setForeground(TEXT);
    t.setGridColor(BORDER);
    t.setShowVerticalLines(false);
    t.setSelectionBackground(new Color(0x2F, 0x6B, 0x5E));
    t.setSelectionForeground(TEXT);
    t.setRowHeight(Math.max(22, t.getRowHeight() + 4));
    t.getTableHeader().setBackground(HEADER);
    t.getTableHeader().setForeground(MUTED);
    t.getTableHeader().setFont(t.getTableHeader().getFont().deriveFont(Font.BOLD));
  }
}
