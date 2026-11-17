package featurecat.lizzie.explorer;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The local opening database: collections, game metadata and the position index.
 *
 * <p>Layout in the database directory:
 *
 * <ul>
 *   <li>{@code library.json}: collections, counters, and the names of the current games file and
 *       index segments. It is written last and atomically, so it always describes a complete set of
 *       files; files it does not name are leftovers and are deleted on open.
 *   <li>{@code games-N.jsonl}: one line per game (id, fingerprint, collections, metadata).
 *   <li>{@code seg-N.bin}: immutable index segments, records sorted by canonical position key. Each
 *       import appends a segment, so adding games never rebuilds the index; segments are merged
 *       when there are many of them.
 * </ul>
 *
 * <p>Not thread-safe: one owner thread ({@link OpeningExplorerService}) does all reads and writes.
 */
public final class OpeningDatabase {
  private static final Logger LOGGER = LoggerFactory.getLogger(OpeningDatabase.class);
  private static final int SCHEMA = 1;
  private static final int SEGMENT_MAGIC = 0x4C5A4F45; // "LZOE"
  private static final int MERGE_THRESHOLD = 8;

  /** A named collection of games. */
  public record Collection(int id, String name, int games) {}

  /** What happened to one game offered to {@link #addGame}. */
  public enum AddResult {
    ADDED,
    /** Same game already indexed; it is now also a member of the target collection. */
    DUPLICATE_LINKED,
    /** Same game already in the target collection; nothing changed. */
    DUPLICATE
  }

  private static final class GameEntry {
    final int id;
    final String fingerprint;
    final Set<Integer> collections;
    final GameMetadata metadata;
    final int moves;

    GameEntry(
        int id, String fingerprint, Set<Integer> collections, GameMetadata metadata, int moves) {
      this.id = id;
      this.fingerprint = fingerprint;
      this.collections = collections;
      this.metadata = metadata;
      this.moves = moves;
    }
  }

  private static final class Segment {
    final String name;
    final long[] hi;
    final long[] lo;
    final int[] game;
    final short[] move;
    final byte[] flags;

    Segment(String name, long[] hi, long[] lo, int[] game, short[] move, byte[] flags) {
      this.name = name;
      this.hi = hi;
      this.lo = lo;
      this.game = game;
      this.move = move;
      this.flags = flags;
    }

    int size() {
      return game.length;
    }

    /** First record index whose key is not below (h, l). */
    int lowerBound(long h, long l) {
      int a = 0;
      int b = game.length;
      while (a < b) {
        int m = (a + b) >>> 1;
        if (PositionKey.compare(hi[m], lo[m], h, l) < 0) a = m + 1;
        else b = m;
      }
      return a;
    }
  }

  private final Path dir;
  private final Map<Integer, String> collectionNames = new LinkedHashMap<>();
  private final Map<Integer, GameEntry> games = new LinkedHashMap<>();
  private final Map<String, Integer> byFingerprint = new HashMap<>();
  private final List<Segment> segments = new ArrayList<>();
  private int nextGameId = 1;
  private int nextCollectionId = 1;
  private int nextFileNumber = 1;
  private String gamesFile;
  private int generation;

  // Pending batch, written by commit().
  private final List<SgfGameIndexer.IndexedGame> pendingGames = new ArrayList<>();
  private final List<Integer> pendingIds = new ArrayList<>();
  private boolean dirtyGames;

  // Filter cache.
  private OpeningFilter cachedFilter;
  private int cachedGeneration = -1;
  private BitSet cachedAllowed;

  private OpeningDatabase(Path dir) {
    this.dir = dir;
  }

  /** Opens (or creates) the database in {@code dir}. Damaged files are reported, never deleted. */
  public static OpeningDatabase open(Path dir) throws IOException {
    Files.createDirectories(dir);
    OpeningDatabase db = new OpeningDatabase(dir);
    db.load();
    return db;
  }

  public Path directory() {
    return dir;
  }

  // ---------------------------------------------------------------- collections

  public List<Collection> collections() {
    Map<Integer, Integer> counts = new HashMap<>();
    for (GameEntry g : games.values()) {
      for (Integer c : g.collections) counts.merge(c, 1, Integer::sum);
    }
    List<Collection> result = new ArrayList<>();
    for (Map.Entry<Integer, String> e : collectionNames.entrySet()) {
      result.add(new Collection(e.getKey(), e.getValue(), counts.getOrDefault(e.getKey(), 0)));
    }
    return result;
  }

  public int createCollection(String name) throws IOException {
    String clean = name == null ? "" : name.trim();
    if (clean.isEmpty()) throw new IllegalArgumentException("Collection name is empty");
    for (Map.Entry<Integer, String> e : collectionNames.entrySet()) {
      if (e.getValue().equalsIgnoreCase(clean)) return e.getKey();
    }
    int id = nextCollectionId++;
    collectionNames.put(id, clean.length() > 80 ? clean.substring(0, 80) : clean);
    writeLibrary();
    return id;
  }

  public void renameCollection(int id, String name) throws IOException {
    String clean = name == null ? "" : name.trim();
    if (!collectionNames.containsKey(id) || clean.isEmpty()) return;
    collectionNames.put(id, clean);
    writeLibrary();
  }

  /** Deletes a collection; games that belong to no other collection leave the index. */
  public void deleteCollection(int id) throws IOException {
    if (collectionNames.remove(id) == null) return;
    List<Integer> orphaned = new ArrayList<>();
    for (GameEntry g : games.values()) {
      if (g.collections.remove(id) && g.collections.isEmpty()) orphaned.add(g.id);
    }
    for (Integer gameId : orphaned) {
      GameEntry g = games.remove(gameId);
      byFingerprint.remove(g.fingerprint);
    }
    generation++;
    dirtyGames = true;
    commit();
  }

  public int gameCount() {
    return games.size();
  }

  // ---------------------------------------------------------------- import

  /** Adds an indexed game to the pending batch (or links a duplicate). Call {@link #commit}. */
  public AddResult addGame(SgfGameIndexer.IndexedGame game, int collectionId) {
    if (!collectionNames.containsKey(collectionId)) {
      throw new IllegalArgumentException("Unknown collection " + collectionId);
    }
    Integer existing = byFingerprint.get(game.fingerprint);
    if (existing != null) {
      GameEntry e = games.get(existing);
      if (e.collections.add(collectionId)) {
        dirtyGames = true;
        generation++;
        return AddResult.DUPLICATE_LINKED;
      }
      return AddResult.DUPLICATE;
    }
    int id = nextGameId++;
    Set<Integer> colls = new LinkedHashSet<>();
    colls.add(collectionId);
    games.put(id, new GameEntry(id, game.fingerprint, colls, game.metadata, game.mainLineMoves));
    byFingerprint.put(game.fingerprint, id);
    pendingGames.add(game);
    pendingIds.add(id);
    dirtyGames = true;
    generation++;
    return AddResult.ADDED;
  }

  /** Writes pending games as a new index segment, then the games file and the library. */
  public void commit() throws IOException {
    if (!pendingGames.isEmpty()) {
      segments.add(writeSegment(buildSegment()));
      pendingGames.clear();
      pendingIds.clear();
    }
    if (segments.size() > MERGE_THRESHOLD) {
      Segment merged = writeSegment(mergeSegments());
      List<Segment> old = new ArrayList<>(segments);
      segments.clear();
      segments.add(merged);
      writeAll();
      for (Segment s : old) Files.deleteIfExists(dir.resolve(s.name));
      return;
    }
    writeAll();
  }

  private void writeAll() throws IOException {
    if (dirtyGames || gamesFile == null) {
      String previous = gamesFile;
      gamesFile = "games-" + (nextFileNumber++) + ".jsonl";
      writeGames(dir.resolve(gamesFile));
      writeLibrary();
      if (previous != null) Files.deleteIfExists(dir.resolve(previous));
      dirtyGames = false;
    } else {
      writeLibrary();
    }
  }

  /** Abandons games added since the last commit. */
  public void rollback() {
    for (Integer id : pendingIds) {
      GameEntry e = games.remove(id);
      if (e != null) byFingerprint.remove(e.fingerprint);
    }
    pendingGames.clear();
    pendingIds.clear();
    generation++;
  }

  // ---------------------------------------------------------------- query

  /** Statistics for a canonical position, restricted to games accepted by {@code filter}. */
  public OpeningStats query(PositionKey key, OpeningFilter filter, boolean includeVariations) {
    BitSet allowed = allowedGames(filter);
    int gamesReaching = 0;
    int ended = 0;
    int raw = 0;
    Map<Integer, int[]> perMove = new HashMap<>(); // move -> {games, raw}
    Set<Integer> distinctGames = new HashSet<>();
    Set<Long> gameMovePairs = new HashSet<>();
    for (Segment s : segments) {
      for (int i = s.lowerBound(key.hi(), key.lo()); i < s.size(); i++) {
        if (s.hi[i] != key.hi() || s.lo[i] != key.lo()) break;
        int g = s.game[i];
        if (!allowed.get(g)) continue;
        int flags = s.flags[i];
        boolean variation = (flags & SgfGameIndexer.FLAG_VARIATION) != 0;
        if (variation && !includeVariations) continue;
        int move = s.move[i];
        raw++;
        int[] counts = perMove.computeIfAbsent(move, k -> new int[2]);
        counts[1]++;
        if (!includeVariations) {
          if ((flags & SgfGameIndexer.FLAG_FIRST) != 0) {
            gamesReaching++;
            counts[0]++;
          }
        } else {
          distinctGames.add(g);
          if (gameMovePairs.add(((long) g << 20) | (move & 0xFFFFF))) counts[0]++;
        }
      }
    }
    if (includeVariations) gamesReaching = distinctGames.size();
    List<OpeningStats.MoveStat> moves = new ArrayList<>();
    for (Map.Entry<Integer, int[]> e : perMove.entrySet()) {
      if (e.getKey() == CanonicalPosition.END) {
        ended = e.getValue()[0];
        continue;
      }
      moves.add(new OpeningStats.MoveStat(e.getKey(), e.getValue()[0], e.getValue()[1]));
    }
    moves.sort(
        Comparator.comparingInt(OpeningStats.MoveStat::games)
            .thenComparingInt(OpeningStats.MoveStat::rawOccurrences)
            .reversed()
            .thenComparingInt(OpeningStats.MoveStat::canonicalMove));
    // A move seen only in repeats of the position (never at a game's first occurrence) keeps a raw
    // count but 0 games; it stays in the list so raw counts add up.
    return new OpeningStats(key, includeVariations, gamesReaching, ended, raw, List.copyOf(moves));
  }

  /** Metadata of the games behind a query (for a future "show games" list); capped. */
  public List<GameMetadata> gamesAt(PositionKey key, OpeningFilter filter, int limit) {
    BitSet allowed = allowedGames(filter);
    Set<Integer> ids = new LinkedHashSet<>();
    for (Segment s : segments) {
      for (int i = s.lowerBound(key.hi(), key.lo()); i < s.size() && ids.size() < limit; i++) {
        if (s.hi[i] != key.hi() || s.lo[i] != key.lo()) break;
        if (allowed.get(s.game[i])) ids.add(s.game[i]);
      }
    }
    List<GameMetadata> result = new ArrayList<>();
    for (Integer id : ids) result.add(games.get(id).metadata);
    return result;
  }

  private BitSet allowedGames(OpeningFilter filter) {
    if (filter.equals(cachedFilter) && cachedGeneration == generation) return cachedAllowed;
    BitSet allowed = new BitSet(nextGameId);
    for (GameEntry g : games.values()) {
      if (filter.accepts(g.collections, g.metadata)) allowed.set(g.id);
    }
    cachedFilter = filter;
    cachedGeneration = generation;
    cachedAllowed = allowed;
    return allowed;
  }

  // ---------------------------------------------------------------- segments

  private Segment buildSegment() {
    int total = 0;
    for (SgfGameIndexer.IndexedGame g : pendingGames) total += g.size();
    long[] hi = new long[total];
    long[] lo = new long[total];
    int[] game = new int[total];
    short[] move = new short[total];
    byte[] flags = new byte[total];
    int n = 0;
    for (int k = 0; k < pendingGames.size(); k++) {
      SgfGameIndexer.IndexedGame g = pendingGames.get(k);
      int id = pendingIds.get(k);
      for (int i = 0; i < g.size(); i++, n++) {
        hi[n] = g.keyHi[i];
        lo[n] = g.keyLo[i];
        game[n] = id;
        move[n] = g.moves[i];
        flags[n] = g.flags[i];
      }
    }
    return sorted("seg-" + (nextFileNumber++) + ".bin", hi, lo, game, move, flags);
  }

  private Segment mergeSegments() {
    int total = 0;
    for (Segment s : segments) total += s.size();
    long[] hi = new long[total];
    long[] lo = new long[total];
    int[] game = new int[total];
    short[] move = new short[total];
    byte[] flags = new byte[total];
    int n = 0;
    for (Segment s : segments) {
      for (int i = 0; i < s.size(); i++) {
        if (!games.containsKey(s.game[i])) continue; // drop deleted games
        hi[n] = s.hi[i];
        lo[n] = s.lo[i];
        game[n] = s.game[i];
        move[n] = s.move[i];
        flags[n] = s.flags[i];
        n++;
      }
    }
    return sorted(
        "seg-" + (nextFileNumber++) + ".bin",
        java.util.Arrays.copyOf(hi, n),
        java.util.Arrays.copyOf(lo, n),
        java.util.Arrays.copyOf(game, n),
        java.util.Arrays.copyOf(move, n),
        java.util.Arrays.copyOf(flags, n));
  }

  private static Segment sorted(
      String name, long[] hi, long[] lo, int[] game, short[] move, byte[] flags) {
    int n = game.length;
    int[] order =
        IntStream.range(0, n)
            .boxed()
            .sorted(
                (a, b) -> {
                  int c = PositionKey.compare(hi[a], lo[a], hi[b], lo[b]);
                  return c != 0 ? c : Integer.compare(game[a], game[b]);
                })
            .mapToInt(Integer::intValue)
            .toArray();
    long[] h2 = new long[n];
    long[] l2 = new long[n];
    int[] g2 = new int[n];
    short[] m2 = new short[n];
    byte[] f2 = new byte[n];
    for (int i = 0; i < n; i++) {
      int o = order[i];
      h2[i] = hi[o];
      l2[i] = lo[o];
      g2[i] = game[o];
      m2[i] = move[o];
      f2[i] = flags[o];
    }
    return new Segment(name, h2, l2, g2, m2, f2);
  }

  private Segment writeSegment(Segment s) throws IOException {
    Path target = dir.resolve(s.name);
    Path tmp = dir.resolve(s.name + ".tmp");
    try (OutputStream raw = Files.newOutputStream(tmp);
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(raw, 1 << 16))) {
      out.writeInt(SEGMENT_MAGIC);
      out.writeInt(SCHEMA);
      out.writeInt(s.size());
      for (int i = 0; i < s.size(); i++) {
        out.writeLong(s.hi[i]);
        out.writeLong(s.lo[i]);
        out.writeInt(s.game[i]);
        out.writeShort(s.move[i]);
        out.writeByte(s.flags[i]);
      }
    }
    move(tmp, target);
    return s;
  }

  private Segment readSegment(String name) throws IOException {
    Path file = dir.resolve(name);
    try (InputStream raw = Files.newInputStream(file);
        DataInputStream in = new DataInputStream(new BufferedInputStream(raw, 1 << 16))) {
      if (in.readInt() != SEGMENT_MAGIC) throw new IOException("Not an index segment: " + name);
      int schema = in.readInt();
      if (schema != SCHEMA) throw new IOException("Unsupported segment schema " + schema);
      int n = in.readInt();
      long expected = 12L + 23L * n;
      if (n < 0 || Files.size(file) != expected) throw new IOException("Truncated segment " + name);
      long[] hi = new long[n];
      long[] lo = new long[n];
      int[] game = new int[n];
      short[] move = new short[n];
      byte[] flags = new byte[n];
      for (int i = 0; i < n; i++) {
        hi[i] = in.readLong();
        lo[i] = in.readLong();
        game[i] = in.readInt();
        move[i] = in.readShort();
        flags[i] = in.readByte();
      }
      return new Segment(name, hi, lo, game, move, flags);
    }
  }

  // ---------------------------------------------------------------- library / games files

  private void load() throws IOException {
    Path library = dir.resolve("library.json");
    if (!Files.exists(library)) return;
    JSONObject o = new JSONObject(Files.readString(library, StandardCharsets.UTF_8));
    if (o.optInt("schema", 0) != SCHEMA) {
      throw new IOException("Unsupported opening database schema " + o.optInt("schema", 0));
    }
    nextGameId = o.optInt("nextGameId", 1);
    nextCollectionId = o.optInt("nextCollectionId", 1);
    nextFileNumber = o.optInt("nextFileNumber", 1);
    JSONArray colls = o.optJSONArray("collections");
    if (colls != null) {
      for (int i = 0; i < colls.length(); i++) {
        JSONObject c = colls.getJSONObject(i);
        collectionNames.put(c.getInt("id"), c.getString("name"));
      }
    }
    gamesFile = o.optString("games", null);
    if (gamesFile != null && !safeName(gamesFile)) throw new IOException("Bad games file name");
    if (gamesFile != null) readGames(dir.resolve(gamesFile));
    JSONArray segs = o.optJSONArray("segments");
    Set<String> keep = new HashSet<>();
    keep.add("library.json");
    if (gamesFile != null) keep.add(gamesFile);
    if (segs != null) {
      for (int i = 0; i < segs.length(); i++) {
        String name = segs.getString(i);
        if (!safeName(name)) throw new IOException("Bad segment name " + name);
        segments.add(readSegment(name));
        keep.add(name);
      }
    }
    // Remove leftovers of an interrupted write (only files this class creates).
    try (var stream = Files.list(dir)) {
      for (Path p : (Iterable<Path>) stream::iterator) {
        String n = p.getFileName().toString();
        boolean ours =
            n.matches("seg-\\d+\\.bin(\\.tmp)?")
                || n.matches("games-\\d+\\.jsonl(\\.tmp)?")
                || n.equals("library.json.tmp");
        if (ours && !keep.contains(n)) {
          try {
            Files.deleteIfExists(p);
          } catch (IOException e) {
            LOGGER.warn("Could not remove stale opening index file {}", p, e);
          }
        }
      }
    }
  }

  private static boolean safeName(String name) {
    return name.matches("[A-Za-z0-9._-]{1,64}") && !name.contains("..");
  }

  private void readGames(Path file) throws IOException {
    try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      String line;
      while ((line = r.readLine()) != null) {
        if (line.isBlank()) continue;
        JSONObject o = new JSONObject(line);
        Set<Integer> colls = new LinkedHashSet<>();
        JSONArray a = o.getJSONArray("c");
        for (int i = 0; i < a.length(); i++) colls.add(a.getInt(i));
        GameEntry e =
            new GameEntry(
                o.getInt("id"),
                o.getString("fp"),
                colls,
                GameMetadata.fromJson(o.getJSONObject("m")),
                o.optInt("n", 0));
        games.put(e.id, e);
        byFingerprint.put(e.fingerprint, e.id);
      }
    }
  }

  private void writeGames(Path file) throws IOException {
    Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
    try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
      for (GameEntry e : games.values()) {
        JSONObject o = new JSONObject();
        o.put("id", e.id);
        o.put("fp", e.fingerprint);
        o.put("c", new JSONArray(e.collections));
        o.put("n", e.moves);
        o.put("m", e.metadata.toJson());
        w.write(o.toString());
        w.write('\n');
      }
    }
    move(tmp, file);
  }

  private void writeLibrary() throws IOException {
    JSONObject o = new JSONObject();
    o.put("schema", SCHEMA);
    o.put("nextGameId", nextGameId);
    o.put("nextCollectionId", nextCollectionId);
    o.put("nextFileNumber", nextFileNumber);
    JSONArray colls = new JSONArray();
    for (Map.Entry<Integer, String> e : collectionNames.entrySet()) {
      colls.put(new JSONObject().put("id", e.getKey()).put("name", e.getValue()));
    }
    o.put("collections", colls);
    if (gamesFile != null) o.put("games", gamesFile);
    JSONArray segs = new JSONArray();
    for (Segment s : segments) segs.put(s.name);
    o.put("segments", segs);
    Path tmp = dir.resolve("library.json.tmp");
    Files.writeString(tmp, o.toString(2), StandardCharsets.UTF_8);
    move(tmp, dir.resolve("library.json"));
  }

  private static void move(Path from, Path to) throws IOException {
    try {
      Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
    }
  }
}
