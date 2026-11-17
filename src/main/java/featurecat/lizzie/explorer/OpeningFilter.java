package featurecat.lizzie.explorer;

import java.util.Locale;
import java.util.Set;

/**
 * Which games take part in a query. Empty strings and zero years mean "no restriction"; an empty
 * collection set means every collection.
 */
public record OpeningFilter(
    Set<Integer> collections, String player, String rank, String event, int minYear, int maxYear) {

  public static final OpeningFilter ALL = new OpeningFilter(Set.of(), "", "", "", 0, 0);

  public OpeningFilter {
    collections = collections == null ? Set.of() : Set.copyOf(collections);
    player = norm(player);
    rank = norm(rank);
    event = norm(event);
  }

  private static String norm(String s) {
    return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
  }

  public OpeningFilter withCollections(Set<Integer> ids) {
    return new OpeningFilter(ids, player, rank, event, minYear, maxYear);
  }

  /** True when the game is in a selected collection and matches every metadata condition. */
  public boolean accepts(Set<Integer> gameCollections, GameMetadata m) {
    if (!collections.isEmpty()) {
      boolean any = false;
      for (Integer c : gameCollections) {
        if (collections.contains(c)) {
          any = true;
          break;
        }
      }
      if (!any) return false;
    }
    if (!player.isEmpty()
        && !contains(m.blackPlayer(), player)
        && !contains(m.whitePlayer(), player)) return false;
    if (!rank.isEmpty() && !contains(m.blackRank(), rank) && !contains(m.whiteRank(), rank))
      return false;
    if (!event.isEmpty() && !contains(m.event(), event)) return false;
    if (minYear > 0 || maxYear > 0) {
      int y = m.year();
      if (y == 0) return false; // unknown date cannot satisfy a year range
      if (minYear > 0 && y < minYear) return false;
      if (maxYear > 0 && y > maxYear) return false;
    }
    return true;
  }

  public boolean restrictsMetadata() {
    return !player.isEmpty() || !rank.isEmpty() || !event.isEmpty() || minYear > 0 || maxYear > 0;
  }

  private static boolean contains(String value, String needle) {
    return value.toLowerCase(Locale.ROOT).contains(needle);
  }
}
