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
  private final java.util.Set<Integer> addedIds = new java.util.HashSet<>();

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
      importer.finish();
    } catch (IOException | RuntimeException e) {
      db.rollback();
      throw e;
    }
    progress.update(done, files.size(), importer.added, "");
    return importer.report(done, stopped);
  }

  /** Where Fox games come from; the application implements it with its Fox client. */
  public interface FoxSource {
    /** Fox UID of an account name, or "" when no account has that name. */
    String findUid(String accountName) throws IOException;

    /** Game ids of one page, newest first; {@code after} is "0" or the previous page's last id. */
    List<String> gameIds(String uid, String after) throws IOException;

    /** SGF text of one game. */
    String gameSgf(String gameId) throws IOException;
  }

  /**
   * Imports up to {@code gamesPerAccount} of the newest games of each Fox account. Accounts that do
   * not exist and games that fail to download are reported, never fatal.
   */
  public static Report importFox(
      OpeningDatabase db,
      List<String> accounts,
      int collectionId,
      boolean includeVariations,
      int gamesPerAccount,
      FoxSource source,
      Progress progress,
      BooleanSupplier cancelled)
      throws IOException {
    OpeningImporter importer = new OpeningImporter(db, collectionId, includeVariations, cancelled);
    int total = Math.max(1, accounts.size() * gamesPerAccount);
    int fetched = 0;
    int accountsDone = 0;
    boolean stopped = false;
    try {
      for (String account : accounts) {
        String name = account.trim();
        if (name.isEmpty()) continue;
        int taken = 0;
        try {
          String uid = source.findUid(name);
          if (uid == null || uid.isEmpty()) throw new IOException("no Fox account with this name");
          String after = "0";
          java.util.Set<String> seen = new java.util.HashSet<>();
          pages:
          while (taken < gamesPerAccount) {
            List<String> ids = source.gameIds(uid, after);
            if (ids.isEmpty()) break;
            for (String id : ids) {
              if (cancelled.getAsBoolean()) {
                stopped = true;
                break pages;
              }
              if (!seen.add(id)) continue;
              if (taken >= gamesPerAccount) break pages;
              taken++;
              fetched++;
              String label = "fox:" + name + ":" + id;
              try {
                importer.importText(source.gameSgf(id), label);
              } catch (IOException | RuntimeException e) {
                importer.fail(label, e);
              }
              progress.update(fetched, total, importer.added, name);
            }
            String last = ids.get(ids.size() - 1);
            if (last.equals(after)) break;
            after = last;
          }
        } catch (IOException | RuntimeException e) {
          importer.fail("fox:" + name, e);
        }
        accountsDone++;
        if (stopped) break;
      }
      importer.finish();
    } catch (IOException | RuntimeException e) {
      db.rollback();
      throw e;
    }
    progress.update(fetched, total, importer.added, "");
    return importer.report(accountsDone, stopped);
  }

  /** Saves the batch, then removes games whose moves repeat an earlier game. */
  private void finish() throws IOException {
    db.commit();
    for (Integer id : db.removeDuplicateGames(OpeningDatabase.MIN_DUPLICATE_MOVES)) {
      duplicates++;
      if (addedIds.remove(id)) added--;
    }
  }

  private Report report(int done, boolean stopped) {
    return new Report(done, added, duplicates, failed, stopped, List.copyOf(failures));
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
    importText(SgfReader.decode(data), label);
  }

  private void importText(String text, String label) throws IOException {
    List<SgfReader.Node> roots = SgfReader.parse(text);
    if (roots.isEmpty()) throw new IllegalArgumentException("no SGF game found");
    for (int i = 0; i < roots.size(); i++) {
      String source = roots.size() == 1 ? label : label + "#" + (i + 1);
      try {
        SgfGameIndexer.IndexedGame game =
            SgfGameIndexer.index(roots.get(i), source, includeVariations);
        switch (db.addGame(game, collectionId)) {
          case ADDED:
            added++;
            addedIds.add(db.lastAddedId());
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
