package featurecat.lizzie.explorer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the {@link OpeningDatabase} on one background thread. Every database read and write runs on
 * that thread, so the database needs no locks; results are handed to {@code callbacks} (the Swing
 * event thread in the application, a direct executor in tests).
 *
 * <p>Queries are coalesced: when the board changes faster than queries finish, only the newest
 * request is answered.
 */
public final class OpeningExplorerService implements AutoCloseable {
  private static final Logger LOGGER = LoggerFactory.getLogger(OpeningExplorerService.class);

  /** Result of a query: the statistics plus the position they were computed for. */
  public record QueryResult(CanonicalPosition position, OpeningStats stats, String error) {}

  /** A running import that can be cancelled. */
  public static final class ImportHandle {
    private final AtomicBoolean cancelled = new AtomicBoolean();

    public void cancel() {
      cancelled.set(true);
    }

    public boolean isCancelled() {
      return cancelled.get();
    }
  }

  private final Path directory;
  private final java.util.concurrent.Executor callbacks;
  private final ExecutorService worker;
  private final AtomicLong latestQuery = new AtomicLong();
  private OpeningDatabase db; // worker thread only
  private IOException openFailure; // worker thread only

  public OpeningExplorerService(Path directory, java.util.concurrent.Executor callbacks) {
    this.directory = directory;
    this.callbacks = callbacks;
    this.worker =
        Executors.newSingleThreadExecutor(
            r -> {
              Thread t = new Thread(r, "opening-explorer");
              t.setDaemon(true);
              t.setPriority(Thread.NORM_PRIORITY - 1);
              return t;
            });
  }

  public Path directory() {
    return directory;
  }

  private OpeningDatabase db() throws IOException {
    if (db == null) {
      if (openFailure != null) throw openFailure;
      try {
        db = OpeningDatabase.open(directory);
      } catch (IOException | RuntimeException e) {
        openFailure = e instanceof IOException io ? io : new IOException(e.getMessage(), e);
        LOGGER.warn("Opening database at {} could not be opened", directory, e);
        throw openFailure;
      }
    }
    return db;
  }

  public void query(
      CanonicalPosition position,
      OpeningFilter filter,
      boolean includeVariations,
      Consumer<QueryResult> result) {
    long ticket = latestQuery.incrementAndGet();
    worker.execute(
        () -> {
          if (ticket != latestQuery.get()) return; // superseded
          QueryResult r;
          try {
            r =
                new QueryResult(
                    position, db().query(position.key(), filter, includeVariations), null);
          } catch (IOException | RuntimeException e) {
            r =
                new QueryResult(
                    position,
                    OpeningStats.empty(position.key(), includeVariations),
                    e.getMessage());
          }
          QueryResult answer = r;
          if (ticket == latestQuery.get()) callbacks.execute(() -> result.accept(answer));
        });
  }

  public void collections(Consumer<List<OpeningDatabase.Collection>> result) {
    worker.execute(
        () -> {
          List<OpeningDatabase.Collection> list;
          try {
            list = db().collections();
          } catch (IOException | RuntimeException e) {
            list = List.of();
          }
          List<OpeningDatabase.Collection> answer = list;
          callbacks.execute(() -> result.accept(answer));
        });
  }

  /** Creates (or finds) the collection {@code name}, then imports {@code files} into it. */
  public ImportHandle importFiles(
      List<Path> files,
      String collectionName,
      boolean includeVariations,
      OpeningImporter.Progress progress,
      Consumer<OpeningImporter.Report> done,
      Consumer<String> failed) {
    ImportHandle handle = new ImportHandle();
    worker.execute(
        () -> {
          try {
            OpeningDatabase d = db();
            int collection = d.createCollection(collectionName);
            OpeningImporter.Report report =
                OpeningImporter.importFiles(
                    d,
                    files,
                    collection,
                    includeVariations,
                    (a, b, c, n) -> callbacks.execute(() -> progress.update(a, b, c, n)),
                    handle::isCancelled);
            callbacks.execute(() -> done.accept(report));
          } catch (IOException | RuntimeException e) {
            LOGGER.warn("Opening import failed", e);
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            callbacks.execute(() -> failed.accept(message));
          }
        });
    return handle;
  }

  /** Creates (or finds) the collection {@code collectionName}, then imports Fox games into it. */
  public ImportHandle importFox(
      List<String> accounts,
      String collectionName,
      boolean includeVariations,
      int gamesPerAccount,
      OpeningImporter.FoxSource source,
      OpeningImporter.Progress progress,
      Consumer<OpeningImporter.Report> done,
      Consumer<String> failed) {
    ImportHandle handle = new ImportHandle();
    worker.execute(
        () -> {
          try {
            OpeningDatabase d = db();
            int collection = d.createCollection(collectionName);
            OpeningImporter.Report report =
                OpeningImporter.importFox(
                    d,
                    accounts,
                    collection,
                    includeVariations,
                    gamesPerAccount,
                    source,
                    (a, b, c, n) -> callbacks.execute(() -> progress.update(a, b, c, n)),
                    handle::isCancelled);
            callbacks.execute(() -> done.accept(report));
          } catch (IOException | RuntimeException e) {
            LOGGER.warn("Fox import failed", e);
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            callbacks.execute(() -> failed.accept(message));
          }
        });
    return handle;
  }

  public void deleteCollection(int id, Runnable done, Consumer<String> failed) {
    worker.execute(
        () -> {
          try {
            db().deleteCollection(id);
            callbacks.execute(done);
          } catch (IOException | RuntimeException e) {
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            callbacks.execute(() -> failed.accept(message));
          }
        });
  }

  /** Games behind one statistics row; see {@link OpeningDatabase#gamesPlaying}. */
  public void gamesPlaying(
      PositionKey key,
      int canonicalMove,
      OpeningFilter filter,
      boolean includeVariations,
      int limit,
      Consumer<List<OpeningDatabase.GameRef>> result) {
    worker.execute(
        () -> {
          List<OpeningDatabase.GameRef> list;
          try {
            list = db().gamesPlaying(key, canonicalMove, filter, includeVariations, limit);
          } catch (IOException | RuntimeException e) {
            LOGGER.warn("Listing opening games failed", e);
            list = List.of();
          }
          List<OpeningDatabase.GameRef> answer = list;
          callbacks.execute(() -> result.accept(answer));
        });
  }

  /** Players, ranks, events and years found in the given collections. */
  public void facets(java.util.Set<Integer> collections, Consumer<OpeningDatabase.Facets> result) {
    worker.execute(
        () -> {
          OpeningDatabase.Facets f;
          try {
            f = db().facets(collections);
          } catch (IOException | RuntimeException e) {
            f =
                new OpeningDatabase.Facets(
                    java.util.Map.of(),
                    java.util.Map.of(),
                    java.util.Map.of(),
                    java.util.Map.of(),
                    0);
          }
          OpeningDatabase.Facets answer = f;
          callbacks.execute(() -> result.accept(answer));
        });
  }

  /** The stored record of a game, or null. */
  public void record(int gameId, Consumer<String> result) {
    worker.execute(
        () -> {
          String text;
          try {
            text = db().record(gameId);
          } catch (IOException | RuntimeException e) {
            LOGGER.warn("Reading game record {} failed", gameId, e);
            text = null;
          }
          String answer = text;
          callbacks.execute(() -> result.accept(answer));
        });
  }

  /**
   * Walks back from the newest position: the first one that games in the filter reached, as {@code
   * {index, games}} (index into {@code keys}, newest first), or {@code {-1, 0}}.
   */
  public void lastMatch(
      List<PositionKey> keys,
      OpeningFilter filter,
      boolean includeVariations,
      Consumer<int[]> result) {
    worker.execute(
        () -> {
          int[] found = {-1, 0};
          try {
            OpeningDatabase d = db();
            for (int i = 0; i < keys.size(); i++) {
              int games = d.query(keys.get(i), filter, includeVariations).games();
              if (games > 0) {
                found = new int[] {i, games};
                break;
              }
            }
          } catch (IOException | RuntimeException e) {
            LOGGER.warn("Searching the last matching position failed", e);
          }
          int[] answer = found;
          callbacks.execute(() -> result.accept(answer));
        });
  }

  public void renameCollection(int id, String name, Runnable done) {
    worker.execute(
        () -> {
          try {
            db().renameCollection(id, name);
          } catch (IOException | RuntimeException e) {
            LOGGER.warn("Renaming opening collection failed", e);
          }
          callbacks.execute(done);
        });
  }

  /** Runs {@code task} on the worker after everything submitted so far (used by tests). */
  void flush(Runnable task) {
    worker.execute(task);
  }

  @Override
  public void close() {
    worker.shutdown();
  }
}
