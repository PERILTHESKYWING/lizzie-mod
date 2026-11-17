package featurecat.lizzie.explorer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Imports SGF files and ZIP archives of SGF files into an {@link OpeningDatabase}. ZIP entries are
 * read in memory and never extracted to disk, so archive paths cannot write anywhere. Work is
 * committed every {@link #BATCH_GAMES} games, so a cancelled import keeps what it finished.
 */
public final class OpeningImporter {
  private static final Logger LOGGER = LoggerFactory.getLogger(OpeningImporter.class);
  static final int BATCH_GAMES = 500;
  static final long MAX_SGF_BYTES = 8L * 1024 * 1024;
  static final int MAX_ZIP_ENTRIES = 200_000;
  private static final int MAX_REPORTED_FAILURES = 50;

  /** Progress callback; called on the importing thread. */
  public interface Progress {
    void update(int filesDone, int filesTotal, int gamesAdded, String current);
  }

  /** Outcome of one import. */
  public record Report(
      int filesRead,
      int gamesAdded,
      int duplicates,
      int failedGames,
      boolean cancelled,
      List<String> failures) {}

  private final OpeningDatabase db;
  private final int collectionId;
  private final boolean includeVariations;
  private final BooleanSupplier cancelled;
  private int added;
  private int duplicates;
  private int failed;
  private int sinceCommit;
  private final List<String> failures = new ArrayList<>();

  private OpeningImporter(
      OpeningDatabase db, int collectionId, boolean includeVariations, BooleanSupplier cancelled) {
    this.db = db;
    this.collectionId = collectionId;
    this.includeVariations = includeVariations;
    this.cancelled = cancelled;
  }

  public static Report importFiles(
      OpeningDatabase db,
      List<Path> files,
      int collectionId,
      boolean includeVariations,
      Progress progress,
      BooleanSupplier cancelled)
      throws IOException {
    OpeningImporter importer = new OpeningImporter(db, collectionId, includeVariations, cancelled);
    int done = 0;
    boolean stopped = false;
    try {
      for (Path file : files) {
        if (cancelled.getAsBoolean()) {
          stopped = true;
          break;
        }
        progress.update(done, files.size(), importer.added, file.getFileName().toString());
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        try {
          if (name.endsWith(".zip")) {
            stopped = importer.importZip(file, progress, done, files.size());
          } else {
            if (Files.size(file) > MAX_SGF_BYTES) throw new IOException("file too large");
            importer.importText(Files.readAllBytes(file), file.getFileName().toString());
          }
        } catch (IOException | RuntimeException e) {
          importer.fail(file.getFileName().toString(), e);
        }
        done++;
        if (stopped) break;
      }
      db.commit();
    } catch (IOException | RuntimeException e) {
      db.rollback();
      throw e;
    }
    progress.update(done, files.size(), importer.added, "");
    return new Report(
        done,
        importer.added,
        importer.duplicates,
        importer.failed,
        stopped,
        List.copyOf(importer.failures));
  }

  /**
   * @return true when cancelled part-way
   */
  private boolean importZip(Path zip, Progress progress, int done, int total) throws IOException {
    int entries = 0;
    try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
      ZipEntry entry;
      while ((entry = in.getNextEntry()) != null) {
        if (cancelled.getAsBoolean()) return true;
        if (++entries > MAX_ZIP_ENTRIES) throw new IOException("too many ZIP entries");
        String entryName = entry.getName();
        if (entry.isDirectory() || !entryName.toLowerCase(Locale.ROOT).endsWith(".sgf")) continue;
        String label = zip.getFileName() + "!" + entryName;
        try {
          importText(readLimited(in), label);
        } catch (IOException | RuntimeException e) {
          fail(label, e);
        }
        if (entries % 50 == 0) progress.update(done, total, added, label);
      }
    }
    return false;
  }

  private static byte[] readLimited(InputStream in) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buf = new byte[8192];
    long total = 0;
    int n;
    while ((n = in.read(buf)) > 0) {
      total += n;
      if (total > MAX_SGF_BYTES) throw new IOException("entry too large");
      out.write(buf, 0, n);
    }
    return out.toByteArray();
  }

  private void importText(byte[] data, String label) throws IOException {
    List<SgfReader.Node> roots = SgfReader.parse(SgfReader.decode(data));
    if (roots.isEmpty()) throw new IllegalArgumentException("no SGF game found");
    for (int i = 0; i < roots.size(); i++) {
      String source = roots.size() == 1 ? label : label + "#" + (i + 1);
      try {
        SgfGameIndexer.IndexedGame game =
            SgfGameIndexer.index(roots.get(i), source, includeVariations);
        switch (db.addGame(game, collectionId)) {
          case ADDED:
            added++;
            if (++sinceCommit >= BATCH_GAMES) {
              db.commit();
              sinceCommit = 0;
            }
            break;
          default:
            duplicates++;
        }
      } catch (RuntimeException e) {
        fail(source, e);
      }
    }
  }

  private void fail(String label, Exception e) {
    failed++;
    LOGGER.info("Opening import skipped {}: {}", label, e.getMessage());
    if (failures.size() < MAX_REPORTED_FAILURES) failures.add(label + ": " + e.getMessage());
  }
}
