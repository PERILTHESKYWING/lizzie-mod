package featurecat.lizzie.gui;

import featurecat.lizzie.Config;
import featurecat.lizzie.util.ModUpdateManifest;
import java.awt.Component;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.ResourceBundle;
import javax.swing.JOptionPane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Remembers which build an update installed and, at the next start, tells the user whether that
 * build is the one running (so an update that silently did not take effect is noticed).
 */
public final class ModUpdateNotice {
  private static final Logger LOGGER = LoggerFactory.getLogger(ModUpdateNotice.class);
  static final String FILE = "mod-update-pending.txt";

  /** The outcome of a pending update, or null when none was pending. */
  record Result(boolean applied, String expected, String running) {}

  private ModUpdateNotice() {}

  static void expect(String commit) throws IOException {
    Path f = file();
    Files.createDirectories(f.getParent());
    Files.writeString(f, commit.trim(), StandardCharsets.UTF_8);
  }

  static void clear() {
    try {
      Files.deleteIfExists(file());
    } catch (IOException e) {
      LOGGER.info("Could not remove {}", FILE, e);
    }
  }

  /** Reads and removes the pending marker in {@code dir}; null when there was none. */
  static Result consume(Path dir, String runningCommit) {
    Path f = dir.resolve(FILE);
    if (!Files.isRegularFile(f)) return null;
    try {
      String expected = Files.readString(f, StandardCharsets.UTF_8).trim();
      Files.deleteIfExists(f);
      String running = runningCommit == null ? "" : runningCommit.trim();
      return new Result(
          !expected.isEmpty() && expected.equalsIgnoreCase(running), expected, running);
    } catch (IOException e) {
      LOGGER.warn("Could not read {}", f, e);
      return null;
    }
  }

  /** Called once the main window is up: reports the result of an update made last session. */
  public static void showPendingResult(Component owner) {
    Result r;
    try {
      r = consume(file().getParent(), ModUpdateManifest.currentBuild().commit());
    } catch (RuntimeException e) {
      return;
    }
    if (r == null) return;
    ResourceBundle bundle = ResourceBundle.getBundle("l10n.DisplayStrings");
    String title = bundle.getString("ModUpdater.title");
    if (r.applied()) {
      JOptionPane.showMessageDialog(
          owner,
          MessageFormat.format(bundle.getString("ModUpdater.updatedTo"), shortCommit(r.expected())),
          title,
          JOptionPane.INFORMATION_MESSAGE);
    } else {
      LOGGER.warn("Mod update to {} did not take effect; running {}", r.expected(), r.running());
      JOptionPane.showMessageDialog(
          owner,
          MessageFormat.format(
              bundle.getString("ModUpdater.notApplied"),
              shortCommit(r.expected()),
              shortCommit(r.running())),
          title,
          JOptionPane.WARNING_MESSAGE);
    }
  }

  private static String shortCommit(String c) {
    return c == null || c.isEmpty() ? "?" : c.substring(0, Math.min(7, c.length()));
  }

  private static Path file() {
    return Config.resolvedWorkDirPath().resolve(FILE);
  }
}
