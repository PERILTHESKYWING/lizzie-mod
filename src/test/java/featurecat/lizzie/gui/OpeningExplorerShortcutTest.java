package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.lang.reflect.Field;
import java.nio.file.Path;
import javax.swing.JPanel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** K (no modifiers) opens and closes the Opening Explorer; modified K keeps its old meaning. */
class OpeningExplorerShortcutTest {
  @TempDir Path tmp;

  /**
   * A frame that only counts explorer toggles; created without running LizzieFrame's constructor.
   */
  static class CountingFrame extends LizzieFrame {
    int toggles;

    @Override
    void toggleOpeningExplorer() {
      toggles++;
    }

    @Override
    public void refresh() {}
  }

  private static CountingFrame allocate() throws Exception {
    Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    f.setAccessible(true);
    return (CountingFrame) ((sun.misc.Unsafe) f.get(null)).allocateInstance(CountingFrame.class);
  }

  private static KeyEvent k(int modifiers) {
    return new KeyEvent(new JPanel(), KeyEvent.KEY_PRESSED, 1, modifiers, KeyEvent.VK_K, 'k');
  }

  @Test
  void plainKTogglesTheExplorer() throws Exception {
    LizzieFrame previousFrame = Lizzie.frame;
    Config previousConfig = Lizzie.config;
    CountingFrame frame = allocate();
    try {
      Lizzie.frame = frame;
      Lizzie.config = ConfigTestHelper.createForTests(tmp);
      Input input = new Input();
      input.keyPressed(k(0));
      assertEquals(1, frame.toggles);
      input.keyPressed(k(InputEvent.CTRL_DOWN_MASK));
      input.keyPressed(k(InputEvent.META_DOWN_MASK));
      input.keyPressed(k(InputEvent.SHIFT_DOWN_MASK));
      assertEquals(1, frame.toggles);
      input.keyPressed(k(0));
      assertEquals(2, frame.toggles);
    } finally {
      Lizzie.frame = previousFrame;
      Lizzie.config = previousConfig;
    }
  }
}
