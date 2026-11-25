package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import featurecat.lizzie.util.ModUpdateManifest;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModUpdaterTest {
  @TempDir Path tmp;

  private static String manifest(String sha, long size) {
    return "{\"schema\":1,\"commit\":\"0123456789abcdef0123456789abcdef01234567\","
        + "\"builtAt\":\"2026-11-18T10:00Z\",\"jar\":\"lizzie-yzy2.5.3-shaded.jar\","
        + "\"sha256\":\""
        + sha
        + "\",\"size\":"
        + size
        + "}";
  }

  @Test
  void manifestIsValidatedAndComparedWithTheRunningBuild() throws Exception {
    ModUpdateManifest.Offer offer = ModUpdateManifest.parse(manifest("a".repeat(64), 25_000_000));
    assertEquals("0123456", offer.shortCommit());
    assertThrows(IOException.class, () -> ModUpdateManifest.parse(manifest("xyz", 25_000_000)));
    assertThrows(IOException.class, () -> ModUpdateManifest.parse(manifest("a".repeat(64), 10)));
    assertThrows(IOException.class, () -> ModUpdateManifest.parse("{\"schema\":2}"));
    ModUpdateManifest.Build dev = ModUpdateManifest.currentBuild(); // no properties in tests
    assertFalse(dev.isPublished());
    assertTrue(ModUpdateManifest.isNewer(dev, offer));
    assertTrue(ModUpdateManifest.manifestUrl(dev).startsWith("https://github.com/"));
    assertTrue(ModUpdateManifest.jarUrl(dev).endsWith("/mod-latest/lizzie-yzy2.5.3-shaded.jar"));
  }

  @Test
  void downloadIsCheckedBeforeItReplacesAnything() throws Exception {
    byte[] jar = new byte[1_200_000];
    new Random(1).nextBytes(jar);
    String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(jar));
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/jar",
        ex -> {
          ex.sendResponseHeaders(200, jar.length);
          ex.getResponseBody().write(jar);
          ex.close();
        });
    server.start();
    try {
      String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/jar";
      Path good = tmp.resolve("good.jar.new");
      ModUpdater.download(url, ModUpdateManifest.parse(manifest(sha, jar.length)), good);
      assertArrayEquals(jar, Files.readAllBytes(good));
      Path bad = tmp.resolve("bad.jar.new");
      assertThrows(
          IOException.class,
          () ->
              ModUpdater.download(
                  url, ModUpdateManifest.parse(manifest("b".repeat(64), jar.length)), bad));
      assertFalse(Files.exists(bad));
      assertFalse(Files.exists(tmp.resolve("bad.jar.new.download")));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void swapKeepsTheOldJarAsBackup() throws Exception {
    Path jar = tmp.resolve("lizzie-yzy2.5.3-shaded.jar");
    Files.writeString(jar, "old");
    Files.writeString(tmp.resolve("lizzie-yzy2.5.3-shaded.jar.new"), "new");
    ModUpdater.swapNow(jar);
    assertEquals("new", Files.readString(jar));
    assertEquals("old", Files.readString(tmp.resolve("lizzie-yzy2.5.3-shaded.jar.bak")));
  }

  @Test
  void windowsHelperWaitsSwapsAndRestarts() throws Exception {
    Path root = Files.createDirectories(tmp.resolve("LizzieYzy Next"));
    Path app = Files.createDirectories(root.resolve("app"));
    Path jar = app.resolve("lizzie-yzy2.5.3-shaded.jar");
    Path exe = Files.writeString(root.resolve("LizzieYzy Next.exe"), "");
    assertEquals(exe, ModUpdater.launcher(jar));
    String script = ModUpdater.windowsHelperScript(jar, exe);
    assertTrue(script.contains("|| goto wait"));
    assertTrue(script.contains(jar + ".new"));
    assertTrue(script.contains("start \"\" \"" + exe + "\""));
    assertTrue(script.contains("\r\n"));
    assertFalse(ModUpdater.windowsHelperScript(jar, null).contains("start \"\""));
  }

  @Test
  void windowsUpdatePointsTheLauncherConfigAtANewlyNamedJar() throws Exception {
    Path app = Files.createDirectories(tmp.resolve("app"));
    Path jar = Files.writeString(app.resolve(ModUpdateManifest.JAR_NAME), "old");
    String cfgText =
        "[Application]\r\napp.mainjar=$APPDIR\\lizzie-yzy2.5.3-shaded.jar\r\n"
            + "app.classpath=$APPDIR\\lizzie-yzy2.5.3-shaded.jar\r\n[JavaOptions]\r\n"
            + "java-options=-Djava.library.path=$APPDIR\r\n";
    Path cfg = Files.write(app.resolve("LizzieYzy Next.cfg"), cfgText.getBytes("ISO-8859-1"));
    Files.writeString(app.resolve("other.cfg"), "unrelated");

    assertEquals(cfg, ModUpdater.launcherConfig(jar));
    String commit = "0123456789abcdef0123456789abcdef01234567";
    Path target = jar.resolveSibling(ModUpdater.versionedJarName(commit));
    assertEquals("lizzie-mod-0123456789ab.jar", target.getFileName().toString());
    assertTrue(ModUpdater.isUpdateJarName(target.getFileName().toString()));
    assertFalse(ModUpdater.isUpdateJarName("evil.jar"));
    Files.writeString(target, "new");

    ModUpdater.repointLauncher(cfg, jar.getFileName().toString(), target.getFileName().toString());

    String now = Files.readString(cfg, java.nio.charset.StandardCharsets.ISO_8859_1);
    assertEquals(cfgText.replace(ModUpdateManifest.JAR_NAME, target.getFileName().toString()), now);
    assertTrue(
        Files.readString(cfg.resolveSibling(cfg.getFileName() + ".bak"))
            .contains(ModUpdateManifest.JAR_NAME));
    assertEquals(jar, ModUpdater.previousJar(cfg), "the old jar can be restored");
    assertEquals(target, ModUpdater.launcherConfig(target).resolveSibling(target.getFileName()));
    assertThrows(IOException.class, () -> ModUpdater.repointLauncher(cfg, "missing.jar", "x.jar"));
  }

  @Test
  void olderDownloadedBuildsAreRemovedButTheRunningAndNewOnesKept() throws Exception {
    Path app = Files.createDirectories(tmp.resolve("app2"));
    Path running = Files.writeString(app.resolve("lizzie-mod-aaaaaaaaaaaa.jar"), "r");
    Path keep = Files.writeString(app.resolve("lizzie-mod-bbbbbbbbbbbb.jar"), "k");
    Path old = Files.writeString(app.resolve("lizzie-mod-cccccccccccc.jar"), "o");
    Path stranger = Files.writeString(app.resolve("lizzie-mod-notes.jar"), "s");

    ModUpdater.removeOldVersionedJars(running, keep);

    assertTrue(Files.exists(running) && Files.exists(keep) && Files.exists(stranger));
    assertFalse(Files.exists(old));
  }

  @Test
  void theNextStartReportsWhetherTheUpdateTookEffect() throws Exception {
    Path dir = Files.createDirectories(tmp.resolve("work"));
    assertEquals(null, ModUpdateNotice.consume(dir, "abc"));
    Files.writeString(dir.resolve(ModUpdateNotice.FILE), "abcdef1234\n");
    ModUpdateNotice.Result ok = ModUpdateNotice.consume(dir, "ABCDEF1234");
    assertTrue(ok.applied());
    assertFalse(Files.exists(dir.resolve(ModUpdateNotice.FILE)), "reported once");
    Files.writeString(dir.resolve(ModUpdateNotice.FILE), "abcdef1234");
    assertFalse(ModUpdateNotice.consume(dir, "1111111").applied());
  }
}
