package featurecat.lizzie.theme;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ThemePackTest {
  @TempDir Path tmp;

  @Test
  void everyBundledThemeIsAValidPack() {
    List<ThemePack> packs = ThemePack.scan(Paths.get("theme"));
    assertTrue(packs.size() >= 5, "bundled themes: " + packs.size());
    for (ThemePack pack : packs) {
      assertTrue(pack.isValid(), pack.name() + " " + pack.errors());
      assertFalse(pack.hasManifest());
      assertTrue(pack.isCompatibleWith("2.5.3"));
    }
  }

  @Test
  void manifestDescribesThePack() throws Exception {
    Path dir = Files.createDirectories(tmp.resolve("walnut"));
    Files.write(dir.resolve("board.png"), new byte[] {1, 2, 3});
    Files.writeString(
        dir.resolve("theme.txt"),
        "{\"board-image\":\"board.png\",\"shadow-size\":80,\"future-key\":1}",
        StandardCharsets.UTF_8);
    Files.writeString(
        dir.resolve("pack.json"),
        "{\"schemaVersion\":1,\"id\":\"walnut\",\"name\":\"Walnut\",\"author\":\"me\","
            + "\"version\":\"1.0.0\",\"compatibleAppVersion\":\">=2.5.0\"}",
        StandardCharsets.UTF_8);
    ThemePack pack = ThemePack.load(dir);
    assertTrue(pack.isValid(), pack.errors().toString());
    assertEquals("Walnut", pack.name());
    assertEquals("me", pack.author());
    assertEquals(List.of("unknown property future-key"), pack.warnings());
    assertTrue(pack.isCompatibleWith("2.5.3"));
    assertFalse(pack.isCompatibleWith("2.4.9"));
  }

  @Test
  void imagesMustStayInsideThePack() throws Exception {
    Path dir = Files.createDirectories(tmp.resolve("bad"));
    Files.writeString(
        dir.resolve("theme.txt"),
        "{\"board-image\":\"../../config.txt\",\"background-image\":\"/etc/passwd.png\","
            + "\"black-stone-image\":\"run.exe\",\"white-stone-image\":\"missing.png\"}",
        StandardCharsets.UTF_8);
    ThemePack pack = ThemePack.load(dir);
    assertFalse(pack.isValid());
    assertEquals(4, pack.errors().size());
    assertNull(ThemePack.resolveImage(dir, "..\\x.png"));
    assertNull(ThemePack.resolveImage(dir, "C:/x.png"));
    assertEquals(
        dir.toAbsolutePath().resolve("img/b.png"), ThemePack.resolveImage(dir, "img/b.png"));
  }

  @Test
  void versionRequirements() {
    assertEquals(0, ThemePack.compareVersions("2.5.3", "2.5.3"));
    assertTrue(ThemePack.compareVersions("2.10", "2.9") > 0);
    assertTrue(ThemePack.compareVersions("2.5", "2.5.1") < 0);
  }
}
