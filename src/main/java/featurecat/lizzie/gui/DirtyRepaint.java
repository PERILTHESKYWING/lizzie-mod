package featurecat.lizzie.gui;

import java.awt.Graphics;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import javax.swing.JComponent;

/**
 * Repaints only the part of the main panel that changed. While the engine analyses, each update
 * changes the boards and the winrate graph, yet the whole window image was composited onto the
 * screen every time; limiting the repaint to those rectangles makes each frame's work follow what
 * changed. Rectangles are in the panel's drawing coordinates (device pixels when the panel undoes
 * the HiDPI scale).
 */
final class DirtyRepaint {
  /** Extra pixels around a region, for anti-aliased edges and rounding. */
  static final int MARGIN = 2;

  /** Where a main or small board renderer draws; null for anything else. */
  static Rectangle bounds(Object renderer) {
    if (renderer instanceof BoardRenderer board) return board.getBoardBounds();
    if (renderer instanceof SubBoardRenderer sub) {
      return new Rectangle(sub.x, sub.y, sub.boardWidth, sub.boardHeight);
    }
    return null;
  }

  /** The union, ignoring null or empty rectangles. */
  static Rectangle union(Rectangle a, Rectangle b) {
    boolean hasA = a != null && !a.isEmpty();
    boolean hasB = b != null && !b.isEmpty();
    if (!hasA) return hasB ? new Rectangle(b) : null;
    return hasB ? a.union(b) : new Rectangle(a);
  }

  /**
   * Repaints {@code dirty} (drawing coordinates), or the whole panel when it is unknown or the
   * drawing scale cannot be mapped reliably (several screens with their own scales).
   */
  static void repaint(JComponent panel, Rectangle dirty, boolean scaled, boolean multiScreen) {
    Rectangle user = toPanel(dirty, scaled, multiScreen, featurecat.lizzie.Lizzie.javaScaleFactor);
    if (user == null) panel.repaint();
    else panel.repaint(user);
  }

  /** {@code dirty} in panel coordinates with a margin, or null for "repaint everything". */
  static Rectangle toPanel(Rectangle dirty, boolean scaled, boolean multiScreen, double scale) {
    if (dirty == null || dirty.isEmpty() || multiScreen) return null;
    double s = scaled && scale > 0 ? scale : 1;
    int x0 = (int) Math.floor((dirty.x - MARGIN) / s);
    int y0 = (int) Math.floor((dirty.y - MARGIN) / s);
    int x1 = (int) Math.ceil((dirty.x + dirty.width + MARGIN) / s);
    int y1 = (int) Math.ceil((dirty.y + dirty.height + MARGIN) / s);
    return new Rectangle(
        Math.max(0, x0), Math.max(0, y0), x1 - Math.max(0, x0), y1 - Math.max(0, y0));
  }

  /** How the main panel paints a frame. */
  enum Paint {
    /** Show the frame drawn already: nothing in it changed. */
    SHOW_FRAME,
    /** Redraw the boards and graph into a copy of the frame. */
    INCREMENTAL,
    /** Redraw everything. */
    FULL
  }

  /**
   * {@code frameUsable}: the frame image exists at the panel's size; {@code frameCurrent}: nothing
   * asked the panel itself to repaint since it was drawn; {@code partialClip}: only part of the
   * panel is painted; {@code incrementalAsked}: only the boards or the graph changed.
   *
   * <p>A partial repaint of an up-to-date frame comes from a transparent component on top (the
   * comment updates on every analysis line); it used to redraw the whole window underneath.
   */
  static Paint decide(
      boolean frameUsable, boolean frameCurrent, boolean partialClip, boolean incrementalAsked) {
    if (!frameUsable) return Paint.FULL;
    if (frameCurrent && partialClip) return Paint.SHOW_FRAME;
    return incrementalAsked ? Paint.INCREMENTAL : Paint.FULL;
  }

  /** Whether {@code g} paints only part of {@code frame} (a component on top repainted). */
  static boolean partial(Graphics g, BufferedImage frame) {
    Rectangle clip = g.getClipBounds();
    return frame != null
        && clip != null
        && !clip.contains(new Rectangle(0, 0, frame.getWidth(), frame.getHeight()));
  }

  /** Whether the last full redraw through a partial clip already asked for a whole repaint. */
  private boolean askedForWhole;

  /**
   * After a full redraw that was painted through a partial clip (the rest of the screen would keep
   * the previous frame), asks once for the whole panel to be shown.
   */
  void afterPaint(JComponent panel, Graphics g, BufferedImage frame, boolean fullRedraw) {
    if (!partial(g, frame)) {
      askedForWhole = false;
    } else if (fullRedraw && !askedForWhole) {
      askedForWhole = true;
      panel.repaint();
    }
  }
}
