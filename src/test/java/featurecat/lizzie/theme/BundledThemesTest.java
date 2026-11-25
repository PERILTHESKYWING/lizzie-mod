package featurecat.lizzie.theme;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The realistic themes ship in the jar and are copied into the theme folder at startup. */
class BundledThemesTest {
  private static final List<String> NAMES = List.of("Hon-kaya", "Bamboo", "Shin-kaya");

  @Test
  void installsEveryBundledThemeOnceAndLeavesUserFoldersAlone(@TempDir Path root) throws Exception {
    Path own = root.resolve("Bamboo");
    Files.createDirectories(own);
    Files.writeString(own.resolve("theme.txt"), "{}", StandardCharsets.UTF_8);

    List<String> written = BundledThemes.install(root);

    assertEquals(List.of("Hon-kaya", "Shin-kaya"), written.stream().sorted().toList());
    assertEquals("{}", Files.readString(own.resolve("theme.txt"), StandardCharsets.UTF_8));
    assertTrue(Files.isRegularFile(root.resolve("Hon-kaya").resolve("board.jpg")));
    assertTrue(BundledThemes.install(root).isEmpty(), "second start writes nothing");
  }

  @Test
  void rewritesAThemeWrittenByAnOlderBundle(@TempDir Path root) throws Exception {
    BundledThemes.install(root);
    Path marker = root.resolve("Shin-kaya").resolve(BundledThemes.MARKER);
    Files.writeString(marker, "0", StandardCharsets.UTF_8);
    Files.delete(root.resolve("Shin-kaya").resolve("white_1.png"));

    assertEquals(List.of("Shin-kaya"), BundledThemes.install(root));
    assertTrue(Files.isRegularFile(root.resolve("Shin-kaya").resolve("white_1.png")));
  }

  @Test
  void everyThemeLoadsItsBoardStonesVariantsAndFalloff(@TempDir Path root) throws Exception {
    BundledThemes.install(root);
    String previous = Theme.pathPrefix;
    try {
      Theme.pathPrefix = root.toString() + java.io.File.separator;
      for (String name : NAMES) {
        Theme theme = new Theme();
        assertTrue(theme.getTheme(new JSONObject().put("theme", name)), name);
        BufferedImage board =
            ImageIO.read(
                root.resolve(name).resolve(theme.config.getString("board-image")).toFile());
        assertNotNull(board, name);
        assertTrue(board.getWidth() >= 2048 && board.getHeight() >= 2048, name);
        List<BufferedImage> whites = theme.stoneVariants(false);
        List<BufferedImage> blacks = theme.stoneVariants(true);
        assertFalse(whites.isEmpty(), name);
        assertFalse(blacks.isEmpty(), name);
        for (BufferedImage stone : whites) {
          assertTrue(stone.getColorModel().hasAlpha(), name + " stones are transparent");
          assertEquals(0, stone.getRGB(0, 0) >>> 24, name + " corner is transparent");
        }
        assertTrue(theme.lightFalloff() > 0 && theme.lightFalloff() < 0.2, name);
      }
      assertEquals(8, themeVariants(root, "Hon-kaya"), "every clamshell differs");
    } finally {
      Theme.pathPrefix = previous;
    }
  }

  @Test
  void themesWithoutVariantsKeepWorking() {
    Theme theme = new Theme();
    theme.config = new JSONObject();
    assertTrue(theme.stoneVariants(true).isEmpty());
    assertEquals(0.0, theme.lightFalloff());
  }

  private static int themeVariants(Path root, String name) throws Exception {
    JSONObject config =
        new JSONObject(
            Files.readString(root.resolve(name).resolve("theme.txt"), StandardCharsets.UTF_8));
    return config.getJSONArray("white-stone-variants").length();
  }
}
