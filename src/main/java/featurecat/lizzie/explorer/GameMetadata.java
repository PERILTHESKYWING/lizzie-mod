package featurecat.lizzie.explorer;

import org.json.JSONObject;

/**
 * SGF game information kept for every indexed game. Missing values are empty strings; numeric
 * values that are absent are {@code NaN} (komi) or 0 (handicap, year).
 */
public record GameMetadata(
    String blackPlayer,
    String whitePlayer,
    String blackRank,
    String whiteRank,
    String date,
    String event,
    String round,
    String place,
    String result,
    int width,
    int height,
    String rules,
    double komi,
    int handicap,
    String source) {

  public GameMetadata {
    blackPlayer = clean(blackPlayer);
    whitePlayer = clean(whitePlayer);
    blackRank = clean(blackRank);
    whiteRank = clean(whiteRank);
    date = clean(date);
    event = clean(event);
    round = clean(round);
    place = clean(place);
    result = clean(result);
    rules = clean(rules);
    source = clean(source);
  }

  private static String clean(String s) {
    if (s == null) return "";
    String t = s.trim().replace('\n', ' ').replace('\r', ' ');
    return t.length() > 200 ? t.substring(0, 200) : t;
  }

  /** The first plausible four-digit year in DT, or 0. */
  public int year() {
    for (int i = 0; i + 4 <= date.length(); i++) {
      boolean digits = true;
      for (int j = i; j < i + 4; j++) digits &= Character.isDigit(date.charAt(j));
      if (digits && (i + 4 == date.length() || !Character.isDigit(date.charAt(i + 4)))) {
        int y = Integer.parseInt(date.substring(i, i + 4));
        if (y >= 1000 && y <= 2999) return y;
      }
      while (i < date.length() && Character.isDigit(date.charAt(i))) i++;
    }
    return 0;
  }

  public JSONObject toJson() {
    JSONObject o = new JSONObject();
    o.put("pb", blackPlayer);
    o.put("pw", whitePlayer);
    o.put("br", blackRank);
    o.put("wr", whiteRank);
    o.put("dt", date);
    o.put("ev", event);
    o.put("ro", round);
    o.put("pc", place);
    o.put("re", result);
    o.put("w", width);
    o.put("h", height);
    o.put("ru", rules);
    if (!Double.isNaN(komi)) o.put("km", komi);
    o.put("ha", handicap);
    o.put("src", source);
    return o;
  }

  public static GameMetadata fromJson(JSONObject o) {
    return new GameMetadata(
        o.optString("pb"),
        o.optString("pw"),
        o.optString("br"),
        o.optString("wr"),
        o.optString("dt"),
        o.optString("ev"),
        o.optString("ro"),
        o.optString("pc"),
        o.optString("re"),
        o.optInt("w", 19),
        o.optInt("h", 19),
        o.optString("ru"),
        o.has("km") ? o.optDouble("km", Double.NaN) : Double.NaN,
        o.optInt("ha", 0),
        o.optString("src"));
  }
}
