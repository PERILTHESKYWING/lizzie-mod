package featurecat.lizzie.explorer;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.mozilla.universalchardet.UniversalDetector;

/**
 * A minimal, side-effect free SGF reader used only for building the opening index.
 *
 * <p>The application's {@code SGFParser} is the parser for opening and editing games, but it writes
 * the global board size and Zobrist tables while parsing, needs the global configuration, and
 * silently drops moves its rule settings reject. That makes it unsafe to call from a background
 * indexing thread while the user keeps working, so bulk import uses this reader instead. It only
 * builds a property tree; it never touches application state.
 */
public final class SgfReader {
  /**
   * One SGF node: properties in file order, children in file order (the first is the main line).
   */
  public static final class Node {
    final Map<String, List<String>> properties = new LinkedHashMap<>();
    final List<Node> children = new ArrayList<>(1);

    public String first(String id) {
      List<String> values = properties.get(id);
      return values == null || values.isEmpty() ? null : values.get(0);
    }

    public List<String> all(String id) {
      List<String> values = properties.get(id);
      return values == null ? List.of() : values;
    }

    public boolean has(String id) {
      return properties.containsKey(id);
    }

    public List<Node> children() {
      return children;
    }

    void add(String id, String value) {
      properties.computeIfAbsent(id, k -> new ArrayList<>(1)).add(value);
    }
  }

  private static final Pattern CHARSET = Pattern.compile("CA\\[([^\\]]{1,40})\\]");

  private final String text;
  private int pos;

  private SgfReader(String text) {
    this.text = text;
  }

  /** Decodes SGF bytes: the CA property first, then strict UTF-8, then charset detection. */
  public static String decode(byte[] data) {
    String head = new String(data, 0, Math.min(data.length, 4096), StandardCharsets.ISO_8859_1);
    Matcher m = CHARSET.matcher(head);
    if (m.find()) {
      try {
        return new String(data, Charset.forName(m.group(1).trim()));
      } catch (RuntimeException ignored) {
        // unknown charset name: fall through to detection
      }
    }
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(data))
          .toString();
    } catch (CharacterCodingException notUtf8) {
      UniversalDetector detector = new UniversalDetector(null);
      detector.handleData(data, 0, data.length);
      detector.dataEnd();
      String detected = detector.getDetectedCharset();
      try {
        if (detected != null) return new String(data, Charset.forName(detected));
      } catch (RuntimeException ignored) {
        // fall back below
      }
      return new String(data, StandardCharsets.ISO_8859_1);
    }
  }

  /**
   * Parses every game tree in an SGF collection.
   *
   * @return the root node of each game; empty when the text holds no game
   * @throws IllegalArgumentException when the text is not well-formed SGF
   */
  public static List<Node> parse(String text) {
    SgfReader reader = new SgfReader(text);
    List<Node> games = new ArrayList<>();
    while (true) {
      int start = text.indexOf('(', reader.pos);
      if (start < 0) break;
      reader.pos = start;
      Node root = reader.readTree(0);
      if (root != null) games.add(root);
    }
    return games;
  }

  /** Reads "( sequence subtrees )" and returns the first node of the sequence. */
  private Node readTree(int depth) {
    if (depth > 1_000) throw new IllegalArgumentException("SGF nesting too deep");
    expect('(');
    Node first = null;
    Node last = null;
    while (true) {
      skipWhitespace();
      if (pos >= text.length()) throw new IllegalArgumentException("Unterminated SGF tree");
      char c = text.charAt(pos);
      if (c == ';') {
        pos++;
        Node node = readProperties();
        if (first == null) first = node;
        else last.children.add(node);
        last = node;
      } else if (c == '(') {
        Node child = readTree(depth + 1);
        if (child != null) {
          if (last == null) {
            // "((;...))": a tree without its own nodes; adopt the subtree.
            first = child;
            last = child;
          } else {
            last.children.add(child);
          }
        }
      } else if (c == ')') {
        pos++;
        return first;
      } else {
        throw new IllegalArgumentException("Unexpected '" + c + "' at " + pos);
      }
    }
  }

  private Node readProperties() {
    Node node = new Node();
    while (true) {
      skipWhitespace();
      if (pos >= text.length()) return node;
      char c = text.charAt(pos);
      if (!Character.isLetter(c)) return node;
      int idStart = pos;
      while (pos < text.length() && Character.isLetter(text.charAt(pos))) pos++;
      // FF[3] allows lower-case letters in identifiers ("PlayerBlack"); keep only capitals.
      StringBuilder id = new StringBuilder();
      for (int i = idStart; i < pos; i++) {
        char ch = text.charAt(i);
        if (Character.isUpperCase(ch)) id.append(ch);
      }
      skipWhitespace();
      boolean any = false;
      while (pos < text.length() && text.charAt(pos) == '[') {
        node.add(id.toString(), readValue());
        any = true;
        skipWhitespace();
      }
      if (!any) throw new IllegalArgumentException("Property without value at " + idStart);
    }
  }

  private String readValue() {
    expect('[');
    StringBuilder value = new StringBuilder();
    while (pos < text.length()) {
      char c = text.charAt(pos++);
      if (c == '\\' && pos < text.length()) {
        char next = text.charAt(pos++);
        if (next == '\n' || next == '\r') { // soft line break
          if (pos < text.length()
              && (text.charAt(pos) == '\n' || text.charAt(pos) == '\r')
              && text.charAt(pos) != next) pos++;
          continue;
        }
        value.append(next);
      } else if (c == ']') {
        return value.toString();
      } else {
        value.append(c);
      }
    }
    throw new IllegalArgumentException("Unterminated property value");
  }

  private void skipWhitespace() {
    while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) pos++;
  }

  private void expect(char c) {
    skipWhitespace();
    if (pos >= text.length() || text.charAt(pos) != c) {
      throw new IllegalArgumentException("Expected '" + c + "' at " + pos);
    }
    pos++;
  }
}
