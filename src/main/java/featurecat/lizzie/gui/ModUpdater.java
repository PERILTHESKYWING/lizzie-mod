package featurecat.lizzie.gui;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.util.ModUpdateManifest;
import featurecat.lizzie.util.NetworkProxy;
import java.awt.Window;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.text.MessageFormat;
import java.util.HexFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.stream.Stream;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Help → Update LizzieYzy (mod): replaces the application jar with the newest build published on
 * this fork's {@code mod-latest} release, after checking its size and SHA-256. The previous jar is
 * kept as {@code .bak} and can be restored from the same menu item.
 *
 * <p>Windows locks a running jar. In the packaged app the launcher reads {@code app\<App>.cfg} to
 * know which jar to start, so the new build is saved under its own name ({@code
 * lizzie-mod-<commit>.jar}) and the config is pointed at it: nothing locked is replaced, and the
 * next start uses the new jar. Without such a config a helper script swaps the files after exit.
 * Elsewhere the files are swapped at once. After the restart {@link ModUpdateNotice} says whether
 * the update took effect.
 */
final class ModUpdater {
  private static final Logger LOGGER = LoggerFactory.getLogger(ModUpdater.class);
  private static final int MANIFEST_LIMIT = 64 * 1024;

  private final Window owner;
  private final ResourceBundle bundle = ResourceBundle.getBundle("l10n.DisplayStrings");

  ModUpdater(Window owner) {
    this.owner = owner;
  }

  void start() {
    ModUpdateManifest.Build build = ModUpdateManifest.currentBuild();
    background(
        () -> {
          String json =
              new String(
                  fetch(ModUpdateManifest.manifestUrl(build), MANIFEST_LIMIT),
                  StandardCharsets.UTF_8);
          ModUpdateManifest.Offer offer = ModUpdateManifest.parse(json);
          SwingUtilities.invokeLater(() -> offer(build, offer));
        });
  }

  private void offer(ModUpdateManifest.Build build, ModUpdateManifest.Offer offer) {
    Path jar = runningJar();
    if (!ModUpdateManifest.isNewer(build, offer)) {
      Path cfg = jar == null ? null : launcherConfig(jar);
      boolean canRestore =
          jar != null
              && (cfg != null
                  ? previousJar(cfg) != null
                  : Files.isRegularFile(sibling(jar, ".bak")));
      Object[] options =
          canRestore ? new Object[] {text("ok"), text("restore")} : new Object[] {text("ok")};
      int choice =
          JOptionPane.showOptionDialog(
              owner,
              format("upToDate", offer.shortCommit()),
              text("title"),
              JOptionPane.DEFAULT_OPTION,
              JOptionPane.INFORMATION_MESSAGE,
              null,
              options,
              options[0]);
      if (canRestore && choice == 1) restore(jar);
      return;
    }
    if (jar == null) {
      message(text("notInstalled"), JOptionPane.WARNING_MESSAGE);
      return;
    }
    String question =
        build.isPublished()
            ? format(
                "available", offer.shortCommit(), offer.builtAt(), build.commit().substring(0, 7))
            : format("availableDev", offer.shortCommit(), offer.builtAt());
    int answer =
        JOptionPane.showConfirmDialog(
            owner,
            question,
            text("title"),
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.QUESTION_MESSAGE);
    if (answer != JOptionPane.OK_OPTION) return;
    Path cfg = isWindows() ? launcherConfig(jar) : null;
    if (cfg != null) {
      Path target = jar.resolveSibling(versionedJarName(offer.commit()));
      background(
          () -> {
            download(ModUpdateManifest.jarUrl(build), offer, target);
            SwingUtilities.invokeLater(() -> repointAndRestart(cfg, jar, target, offer.commit()));
          });
      return;
    }
    background(
        () -> {
          download(ModUpdateManifest.jarUrl(build), offer, sibling(jar, ".new"));
          SwingUtilities.invokeLater(() -> applyWhenReady(jar));
        });
  }

  /** Points the launcher at {@code target}, remembers what to expect, and offers a restart. */
  private void repointAndRestart(Path cfg, Path jar, Path target, String commit) {
    try {
      repointLauncher(cfg, jar.getFileName().toString(), target.getFileName().toString());
      ModUpdateNotice.expect(commit);
      removeOldVersionedJars(jar, target);
    } catch (IOException e) {
      message(format("failed", e.getMessage()), JOptionPane.WARNING_MESSAGE);
      return;
    }
    offerRestart(text("readyNextStart"), launcher(jar));
  }

  private void offerRestart(String question, Path launcherExe) {
    Object[] options = {text("restartNow"), text("later")};
    int choice =
        JOptionPane.showOptionDialog(
            owner,
            question,
            text("title"),
            JOptionPane.DEFAULT_OPTION,
            JOptionPane.INFORMATION_MESSAGE,
            null,
            options,
            options[0]);
    if (choice != 0) return;
    if (launcherExe != null) {
      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(
                  () -> {
                    try {
                      // a short wait so the new instance starts after this one has let go
                      new ProcessBuilder(
                              "cmd",
                              "/c",
                              "ping",
                              "-n",
                              "3",
                              "127.0.0.1",
                              ">nul",
                              "&",
                              "start",
                              "\"\"",
                              launcherExe.toString())
                          .start();
                    } catch (IOException e) {
                      LOGGER.warn("Could not restart LizzieYzy", e);
                    }
                  },
                  "mod-update-restart"));
    }
    Lizzie.shutdown();
  }

  static String versionedJarName(String commit) {
    return "lizzie-mod-" + commit.substring(0, Math.min(12, commit.length())) + ".jar";
  }

  static boolean isUpdateJarName(String name) {
    return name.equals(ModUpdateManifest.JAR_NAME)
        || name.matches("lizzie-mod-[0-9a-f]{7,40}\\.jar");
  }

  /** The launcher config ({@code <App>.cfg}) next to {@code jar} that names it, or null. */
  static Path launcherConfig(Path jar) {
    Path dir = jar.getParent();
    if (dir == null) return null;
    String name = jar.getFileName().toString();
    try (Stream<Path> files = Files.list(dir)) {
      return files
          .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".cfg"))
          .filter(p -> readLatin1(p).contains(name))
          .findFirst()
          .orElse(null);
    } catch (IOException e) {
      return null;
    }
  }

  /**
   * Replaces the jar file name in the launcher config (its {@code app.classpath}/{@code
   * app.mainjar} lines) and keeps the previous config as {@code .bak}. Bytes are kept as they are.
   */
  static void repointLauncher(Path cfg, String oldJar, String newJar) throws IOException {
    String text = readLatin1(cfg);
    String updated = text.replace(oldJar, newJar);
    if (updated.equals(text)) throw new IOException("the launcher config does not name " + oldJar);
    Files.copy(cfg, sibling(cfg, ".bak"), StandardCopyOption.REPLACE_EXISTING);
    Path tmp = sibling(cfg, ".tmp");
    Files.write(tmp, updated.getBytes(StandardCharsets.ISO_8859_1));
    Files.move(tmp, cfg, StandardCopyOption.REPLACE_EXISTING);
  }

  /** The jar the backup config names, when it still exists (the version to restore), or null. */
  static Path previousJar(Path cfg) {
    Path bak = sibling(cfg, ".bak");
    if (!Files.isRegularFile(bak)) return null;
    String text = readLatin1(bak);
    try (Stream<Path> files = Files.list(cfg.getParent())) {
      return files
          .filter(p -> isUpdateJarName(p.getFileName().toString()))
          .filter(p -> text.contains(p.getFileName().toString()))
          .filter(p -> !readLatin1(cfg).contains(p.getFileName().toString()))
          .findFirst()
          .orElse(null);
    } catch (IOException e) {
      return null;
    }
  }

  /** Keeps the running jar (the backup) and the new one; removes older downloaded builds. */
  static void removeOldVersionedJars(Path running, Path keep) {
    try (Stream<Path> files = Files.list(running.getParent())) {
      files
          .filter(p -> p.getFileName().toString().startsWith("lizzie-mod-"))
          .filter(p -> isUpdateJarName(p.getFileName().toString()))
          .filter(p -> !p.equals(running) && !p.equals(keep))
          .forEach(
              p -> {
                try {
                  Files.deleteIfExists(p);
                } catch (IOException e) {
                  LOGGER.info("Could not remove old build {}", p, e);
                }
              });
    } catch (IOException e) {
      LOGGER.info("Could not list old builds next to {}", running, e);
    }
  }

  private static String readLatin1(Path p) {
    try {
      return new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1);
    } catch (IOException e) {
      return "";
    }
  }

  private static boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
  }

  /** Downloads to {@code .download}, verifies size and SHA-256, then renames to {@code target}. */
  static void download(String url, ModUpdateManifest.Offer offer, Path target) throws Exception {
    Path part = target.resolveSibling(target.getFileName() + ".download");
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    long total = 0;
    URLConnection c = NetworkProxy.openConnection(URI.create(url).toURL());
    c.setConnectTimeout(15000);
    c.setReadTimeout(60000);
    try (InputStream in = c.getInputStream();
        OutputStream out = Files.newOutputStream(part)) {
      byte[] buf = new byte[1 << 16];
      int n;
      while ((n = in.read(buf)) > 0) {
        total += n;
        if (total > offer.jarSize()) throw new IOException("download is larger than announced");
        digest.update(buf, 0, n);
        out.write(buf, 0, n);
      }
    }
    String sha = HexFormat.of().formatHex(digest.digest());
    if (total != offer.jarSize() || !sha.equals(offer.jarSha256())) {
      Files.deleteIfExists(part);
      throw new IOException("downloaded file failed the SHA-256/size check; nothing was changed");
    }
    Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
  }

  private void restore(Path jar) {
    Path cfg = isWindows() ? launcherConfig(jar) : null;
    Path previous = cfg == null ? null : previousJar(cfg);
    if (previous != null) {
      try {
        repointLauncher(cfg, jar.getFileName().toString(), previous.getFileName().toString());
        ModUpdateNotice.clear();
      } catch (IOException e) {
        message(format("failed", e.getMessage()), JOptionPane.WARNING_MESSAGE);
        return;
      }
      offerRestart(text("readyNextStart"), launcher(jar));
      return;
    }
    try {
      Files.copy(sibling(jar, ".bak"), sibling(jar, ".new"), StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      message(format("failed", e.getMessage()), JOptionPane.WARNING_MESSAGE);
      return;
    }
    applyWhenReady(jar);
  }

  /** Swaps {@code .new} in (now, or after exit on Windows) and offers a restart. */
  private void applyWhenReady(Path jar) {
    boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    if (!windows) {
      try {
        swapNow(jar);
      } catch (IOException e) {
        message(format("failed", e.getMessage()), JOptionPane.WARNING_MESSAGE);
        return;
      }
      message(text("readyRestart"), JOptionPane.INFORMATION_MESSAGE);
      return;
    }
    Object[] options = {text("restartNow"), text("later")};
    int choice =
        JOptionPane.showOptionDialog(
            owner,
            text("readyWindows"),
            text("title"),
            JOptionPane.DEFAULT_OPTION,
            JOptionPane.INFORMATION_MESSAGE,
            null,
            options,
            options[0]);
    boolean restart = choice == 0;
    try {
      Path script = writeWindowsHelper(jar, restart ? launcher(jar) : null);
      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(
                  () -> {
                    try {
                      new ProcessBuilder("cmd", "/c", "start", "\"\"", "/min", script.toString())
                          .start();
                    } catch (IOException e) {
                      LOGGER.warn("Could not start the update helper", e);
                    }
                  },
                  "mod-update-helper"));
    } catch (IOException e) {
      message(format("failed", e.getMessage()), JOptionPane.WARNING_MESSAGE);
      return;
    }
    if (restart) Lizzie.shutdown();
  }

  static void swapNow(Path jar) throws IOException {
    Files.move(jar, sibling(jar, ".bak"), StandardCopyOption.REPLACE_EXISTING);
    try {
      Files.move(sibling(jar, ".new"), jar, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      Files.move(sibling(jar, ".bak"), jar, StandardCopyOption.REPLACE_EXISTING);
      throw e;
    }
  }

  /** A CMD script that waits until the jar is unlocked, swaps it, and optionally restarts. */
  static String windowsHelperScript(Path jar, Path launcherExe) {
    String j = jar.toString();
    StringBuilder s = new StringBuilder();
    s.append("@echo off\r\nsetlocal\r\nset N=0\r\n:wait\r\nset /a N+=1\r\n");
    s.append("if %N% gtr 180 exit /b 1\r\nping -n 2 127.0.0.1 >nul\r\n");
    s.append("move /Y \"")
        .append(j)
        .append("\" \"")
        .append(j)
        .append(".bak\" >nul 2>&1 || goto wait\r\n");
    s.append("move /Y \"").append(j).append(".new\" \"").append(j).append("\" >nul 2>&1 || (\r\n");
    s.append("  move /Y \"")
        .append(j)
        .append(".bak\" \"")
        .append(j)
        .append("\" >nul\r\n  exit /b 1\r\n)\r\n");
    if (launcherExe != null) s.append("start \"\" \"").append(launcherExe).append("\"\r\n");
    s.append("del \"%~f0\"\r\n");
    return s.toString();
  }

  private static Path writeWindowsHelper(Path jar, Path launcher) throws IOException {
    Path script = Files.createTempFile("lizzie-mod-update-", ".cmd");
    Files.writeString(script, windowsHelperScript(jar, launcher), StandardCharsets.UTF_8);
    return script;
  }

  /** The jpackage launcher next to the app folder ({@code <root>\LizzieYzy Next*.exe}), or null. */
  static Path launcher(Path jar) {
    Path root = jar.getParent() == null ? null : jar.getParent().getParent();
    if (root == null) return null;
    try (Stream<Path> files = Files.list(root)) {
      return files
          .filter(p -> p.getFileName().toString().startsWith("LizzieYzy"))
          .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".exe"))
          .findFirst()
          .orElse(null);
    } catch (IOException e) {
      return null;
    }
  }

  /** The running application jar when it can be replaced, else null. */
  static Path runningJar() {
    try {
      Path p =
          Path.of(ModUpdater.class.getProtectionDomain().getCodeSource().getLocation().toURI());
      if (!isUpdateJarName(p.getFileName().toString())) return null;
      return Files.isWritable(p.getParent()) ? p : null;
    } catch (Exception e) {
      return null;
    }
  }

  private static Path sibling(Path jar, String suffix) {
    return jar.resolveSibling(jar.getFileName() + suffix);
  }

  private static byte[] fetch(String url, int limit) throws IOException {
    URLConnection c = NetworkProxy.openConnection(URI.create(url).toURL());
    c.setConnectTimeout(15000);
    c.setReadTimeout(30000);
    try (InputStream in = c.getInputStream()) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buf = new byte[8192];
      int n;
      while ((n = in.read(buf)) > 0) {
        if (out.size() + n > limit) throw new IOException("update manifest too large");
        out.write(buf, 0, n);
      }
      return out.toByteArray();
    }
  }

  private interface Work {
    void run() throws Exception;
  }

  private void background(Work work) {
    Thread t =
        new Thread(
            () -> {
              try {
                work.run();
              } catch (Exception e) {
                LOGGER.warn("Mod update failed", e);
                String reason = e.getMessage() == null ? e.toString() : e.getMessage();
                SwingUtilities.invokeLater(
                    () -> message(format("failed", reason), JOptionPane.WARNING_MESSAGE));
              }
            },
            "mod-update");
    t.setDaemon(true);
    t.start();
  }

  private void message(String text, int type) {
    JOptionPane.showMessageDialog(owner, text, text("title"), type);
  }

  private String text(String key) {
    try {
      return bundle.getString("ModUpdater." + key);
    } catch (MissingResourceException e) {
      return key;
    }
  }

  private String format(String key, Object... args) {
    return MessageFormat.format(text(key), args);
  }
}
