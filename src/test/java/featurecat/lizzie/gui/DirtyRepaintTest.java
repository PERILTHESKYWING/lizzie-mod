package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JPanel;
import org.junit.jupiter.api.Test;

class DirtyRepaintTest {
  @Test
  void regionsAreMappedToPanelCoordinatesWithAMargin() {
    assertEquals(
        new Rectangle(8, 18, 104, 54),
        DirtyRepaint.toPanel(new Rectangle(10, 20, 100, 50), false, false, 1));
    assertEquals(
        new Rectangle(4, 9, 52, 27),
        DirtyRepaint.toPanel(new Rectangle(10, 20, 100, 50), true, false, 2),
        "a scaled panel draws in device pixels");
    assertNull(DirtyRepaint.toPanel(new Rectangle(10, 20, 100, 50), true, true, 2));
    assertNull(DirtyRepaint.toPanel(null, false, false, 1), "unknown: repaint everything");
  }

  @Test
  void aComponentOnTopOfAnUnchangedFrameDoesNotRedrawTheWindow() {
    assertEquals(DirtyRepaint.Paint.SHOW_FRAME, DirtyRepaint.decide(true, true, true, false));
    assertEquals(DirtyRepaint.Paint.SHOW_FRAME, DirtyRepaint.decide(true, true, true, true));
    assertEquals(DirtyRepaint.Paint.FULL, DirtyRepaint.decide(true, false, true, false));
    assertEquals(DirtyRepaint.Paint.FULL, DirtyRepaint.decide(true, true, false, false));
    assertEquals(DirtyRepaint.Paint.INCREMENTAL, DirtyRepaint.decide(true, false, true, true));
    assertEquals(DirtyRepaint.Paint.INCREMENTAL, DirtyRepaint.decide(true, true, false, true));
    assertEquals(DirtyRepaint.Paint.FULL, DirtyRepaint.decide(false, true, true, true));
  }

  @Test
  void unionIgnoresMissingRegions() {
    Rectangle a = new Rectangle(0, 0, 10, 10);
    assertEquals(a, DirtyRepaint.union(a, null));
    assertEquals(a, DirtyRepaint.union(null, a));
    assertEquals(new Rectangle(0, 0, 30, 10), DirtyRepaint.union(a, new Rectangle(20, 0, 10, 10)));
    assertNull(DirtyRepaint.union(null, new Rectangle()));
  }

  @Test
  void aFullRedrawThroughAPartialClipAsksOnceForTheWholePanel() {
    AtomicInteger repaints = new AtomicInteger();
    JPanel panel =
        new JPanel() {
          @Override
          public void repaint(long tm, int x, int y, int width, int height) {
            repaints.incrementAndGet();
          }
        };
    BufferedImage frame = new BufferedImage(50, 40, BufferedImage.TYPE_INT_ARGB);
    Graphics2D partial = frame.createGraphics();
    partial.setClip(0, 0, 10, 10);
    Graphics2D whole = frame.createGraphics();
    DirtyRepaint dirty = new DirtyRepaint();
    repaints.set(0); // the panel repaints itself while it is built

    assertTrue(DirtyRepaint.partial(partial, frame));
    assertFalse(DirtyRepaint.partial(whole, frame));
    dirty.afterPaint(panel, partial, frame, false);
    assertEquals(0, repaints.get(), "a component on top repainted: nothing to add");
    dirty.afterPaint(panel, partial, frame, true);
    dirty.afterPaint(panel, partial, frame, true);
    assertEquals(1, repaints.get(), "asked once, never in a loop");
    dirty.afterPaint(panel, whole, frame, true);
    dirty.afterPaint(panel, partial, frame, true);
    assertEquals(2, repaints.get());
  }
}
