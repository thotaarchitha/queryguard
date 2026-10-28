package dev.queryguard.common.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A small PostgreSQL-aware tokenizer.
 *
 * <p>It isn't a parser. It exists for three jobs the real parser can't do well:
 * fingerprinting (literals out, structure kept), splitting a multi-statement Q message
 * without being fooled by semicolons inside strings, and giving the rule engine
 * <em>something</em> to look at when JSqlParser chokes on Postgres-only syntax.
 *
 * <p>Comments are dropped on purpose. {@code DEL/*x*&#47;ETE} isn't a thing in Postgres,
 * but {@code DELETE /* where id = 1 *&#47; FROM orders} is, and we don't want a comment
 * to look like a WHERE clause.
 */
public final class SqlLexer {

    public enum Type { WORD, QUOTED_IDENT, NUMBER, STRING, PARAM, PUNCT, SEMICOLON }

    public record Token(Type type, String text, int start, int end) {
        public boolean isWord(String w) {
            return type == Type.WORD && text.equalsIgnoreCase(w);
        }

        public boolean isPunct(String p) {
            return type == Type.PUNCT && text.equals(p);
        }

        /** Identifier as Postgres would fold it: unquoted lowercased, quoted kept as-is. */
        public String ident() {
            return type == Type.QUOTED_IDENT ? text : text.toLowerCase(Locale.ROOT);
        }
    }

    private static final Pattern IN_LIST = Pattern.compile("\\(\\s*\\?(?:\\s*,\\s*\\?)+\\s*\\)");

    private SqlLexer() {
    }

    public static List<Token> tokenize(String sql) {
        List<Token> out = new ArrayList<>();
        int n = sql.length();
        int i = 0;
        while (i < n) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                int nl = sql.indexOf('\n', i);
                i = nl < 0 ? n : nl + 1;
                continue;
            }
            if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                i = skipBlockComment(sql, i);
                continue;
            }
            int start = i;
            if (c == '\'') {
                i = skipString(sql, i, false);
                out.add(new Token(Type.STRING, sql.substring(start, i), start, i));
                continue;
            }
            if ((c == 'E' || c == 'e') && i + 1 < n && sql.charAt(i + 1) == '\'') {
                i = skipString(sql, i + 1, true);
                out.add(new Token(Type.STRING, sql.substring(start, i), start, i));
                continue;
            }
            if (c == '"') {
                StringBuilder ident = new StringBuilder();
                i++;
                while (i < n) {
                    char q = sql.charAt(i);
                    if (q == '"') {
                        if (i + 1 < n && sql.charAt(i + 1) == '"') {
                            ident.append('"');
                            i += 2;
                            continue;
                        }
                        i++;
                        break;
                    }
                    ident.append(q);
                    i++;
                }
                out.add(new Token(Type.QUOTED_IDENT, ident.toString(), start, i));
                continue;
            }
            if (c == '$') {
                if (i + 1 < n && Character.isDigit(sql.charAt(i + 1))) {
                    int j = i + 1;
                    while (j < n && Character.isDigit(sql.charAt(j))) {
                        j++;
                    }
                    out.add(new Token(Type.PARAM, sql.substring(start, j), start, j));
                    i = j;
                    continue;
                }
                int tagEnd = dollarTagEnd(sql, i);
                if (tagEnd > 0) {
                    String tag = sql.substring(i, tagEnd);
                    int close = sql.indexOf(tag, tagEnd);
                    i = close < 0 ? n : close + tag.length();
                    out.add(new Token(Type.STRING, sql.substring(start, i), start, i));
                    continue;
                }
            }
            if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(sql.charAt(i + 1)))) {
                i = skipNumber(sql, i);
                out.add(new Token(Type.NUMBER, sql.substring(start, i), start, i));
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                int j = i + 1;
                while (j < n && isIdentPart(sql.charAt(j))) {
                    j++;
                }
                out.add(new Token(Type.WORD, sql.substring(start, j), start, j));
                i = j;
                continue;
            }
            if (c == ';') {
                out.add(new Token(Type.SEMICOLON, ";", start, i + 1));
                i++;
                continue;
            }
            if (c == ':' && i + 1 < n && sql.charAt(i + 1) == ':') {
                out.add(new Token(Type.PUNCT, "::", start, i + 2));
                i += 2;
                continue;
            }
            out.add(new Token(Type.PUNCT, String.valueOf(c), start, i + 1));
            i++;
        }
        return out;
    }

    /**
     * Literal-free, lowercased, single-spaced form. {@code WHERE id = 5} and
     * {@code where ID=9} come out identical, which is the whole point.
     */
    public static String normalize(List<Token> tokens) {
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < tokens.size(); k++) {
            Token t = tokens.get(k);
            if (t.type() == Type.SEMICOLON && k == tokens.size() - 1) {
                break; // a trailing ; shouldn't change the fingerprint
            }
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            switch (t.type()) {
                case WORD -> sb.append(t.text().toLowerCase(Locale.ROOT));
                case QUOTED_IDENT -> sb.append('"').append(t.text()).append('"');
                case NUMBER, STRING, PARAM -> sb.append('?');
                case PUNCT, SEMICOLON -> sb.append(t.text());
            }
        }
        // IN (1,2,3) and IN (4,5) are the same query shape.
        return IN_LIST.matcher(sb).replaceAll("( ?+ )");
    }

    /** Splits on top-level semicolons. Empty statements ({@code ;;}) are dropped. */
    public static List<List<Token>> splitStatements(List<Token> tokens) {
        List<List<Token>> out = new ArrayList<>();
        int from = 0;
        for (int k = 0; k <= tokens.size(); k++) {
            if (k == tokens.size() || tokens.get(k).type() == Type.SEMICOLON) {
                if (k > from) {
                    out.add(tokens.subList(from, k));
                }
                from = k + 1;
            }
        }
        return out;
    }

    private static boolean isIdentPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    private static int skipBlockComment(String sql, int i) {
        // Postgres block comments nest, unlike most databases.
        int depth = 0;
        int n = sql.length();
        while (i < n) {
            if (sql.startsWith("/*", i)) {
                depth++;
                i += 2;
            } else if (sql.startsWith("*/", i)) {
                depth--;
                i += 2;
                if (depth == 0) {
                    return i;
                }
            } else {
                i++;
            }
        }
        return n;
    }

    private static int skipString(String sql, int quote, boolean backslashEscapes) {
        int n = sql.length();
        int j = quote + 1;
        while (j < n) {
            char ch = sql.charAt(j);
            if (backslashEscapes && ch == '\\') {
                j += 2;
                continue;
            }
            if (ch == '\'') {
                if (j + 1 < n && sql.charAt(j + 1) == '\'') {
                    j += 2;
                    continue;
                }
                return j + 1;
            }
            j++;
        }
        return n;
    }

    private static int dollarTagEnd(String sql, int i) {
        int j = i + 1;
        while (j < sql.length() && sql.charAt(j) != '$') {
            char ch = sql.charAt(j);
            if (!(Character.isLetterOrDigit(ch) || ch == '_')) {
                return -1;
            }
            j++;
        }
        return j < sql.length() ? j + 1 : -1;
    }

    private static int skipNumber(String sql, int i) {
        int n = sql.length();
        while (i < n && (Character.isDigit(sql.charAt(i)) || sql.charAt(i) == '.' || sql.charAt(i) == '_')) {
            i++;
        }
        if (i < n && (sql.charAt(i) == 'e' || sql.charAt(i) == 'E')) {
            int j = i + 1;
            if (j < n && (sql.charAt(j) == '+' || sql.charAt(j) == '-')) {
                j++;
            }
            if (j < n && Character.isDigit(sql.charAt(j))) {
                i = j;
                while (i < n && Character.isDigit(sql.charAt(i))) {
                    i++;
                }
            }
        }
        return i;
    }
}
