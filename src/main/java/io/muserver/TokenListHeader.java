package io.muserver;

import java.util.*;
import java.util.stream.Collectors;

import static io.muserver.Mutils.notNull;
import static java.util.Collections.emptyList;

/**
 * A utility class to parse headers that are of the format <code>token1, token2, token3</code>
 * such as <code>Vary</code> etc.
 * <p>Note: a &quot;token&quot; is a string of characters matching A-Z, a-z, 0-9, or <code>!#$%&amp;'*+-.^_`|~</code> as defined
 * in <a href="https://httpwg.org/specs/rfc9110.html#rfc.section.5.6.2">RFC 9110 section 5.6.2</a>
 * and are used as list values for some HTTP headers such as <code>vary</code>.</p>
 */
public class TokenListHeader {

    private final ArrayList<String> tokens;

    /**
     * Creates a value with tokens
     *
     * @param tokens a list of tokens
     */
    private TokenListHeader(List<String> tokens) {
        notNull("tokens", tokens);
        this.tokens = tokens.stream().filter(s -> !Mutils.nullOrEmpty(s)).collect(Collectors.toCollection(ArrayList::new));
    }

    /**
     * @return Gets all the tokens
     */
    public List<String> tokens() {
        return tokens;
    }


    /**
     * Returns <code>true</code> if the token is in the list
     *
     * @param token      The token to look up
     * @param ignoreCase Whether to ignore case when checking if the token is already in the list
     * @return <code>true</code> if this has the token
     */
    public boolean contains(String token, boolean ignoreCase) {
        if (!ignoreCase)
        return tokens.contains(token);
        else
            return  tokens.stream().anyMatch(t -> t.equalsIgnoreCase(token));
    }

    /**
     * Removes the given token from the list, if it is present.
     * <p>If it is listed multiple times then all are removed</p>
     * @param token the token to remove
     * @return <code>true</code> if it was removed; <code>false</code> if it wasn't in the list
     */
    public boolean remove(String token) {
        var removed = false;
        while (tokens.contains(token)) {
            tokens.remove(token);
            removed = true;
        }
        return removed;
    }

    /**
     * Adds the token to the list.
     * <p>If the token is already in the list it will be duplicated.</p>
     * @param token the token to add
     * @see #addIfMissing(String, boolean)
     */
    public void add(String token) {
        tokens.add(token);
    }

    /**
     * Adds the token to the list if it isn't already included
     *
     * @param token      the token to add
     * @param ignoreCase <code>true</code> if case should be ignored, for example <code>Connection</code>
     *                   would not be added if <code>connection</code> was already present.
     * @return <code>true</code> if the token was added; <code>false</code> if it was already in the list.
     */
    public boolean addIfMissing(String token, boolean ignoreCase) {
        var already = contains(token, ignoreCase);
        if (!already) tokens.add(token);
        return !already;
    }

    /**
     * Converts a comma-separated list of tokens into a list.
     * <p>Null or blank strings are ignored.</p>
     * <p>Example usage to get the <code>vary</code> tokens from a response header object:</p>
     * <pre><code>
     *     var tokenList = TokenListHeader.parse(response.headers().getAll("vary"));
     * </code></pre>
     * @param input The value to parse
     * @param allowDuplicates if <code>true</code> then duplicate values are retained; if <code>false</code>
     *                        then duplicate values are discarded.
     * @return a list of tokens, or an empty list if there are none
     * @throws IllegalArgumentException The value cannot be parsed
     */
    public static TokenListHeader parse(List<String> input, boolean allowDuplicates) {
        if (input == null || input.isEmpty()) {
            return new TokenListHeader(emptyList());
        }
        var toks = new ArrayList<String>();
        for (String csv : input) {
            var bits = csv.split("\\s*,\\s*");
            for (String bit : bits) {
                var trimmed = bit.trim();
                if (!trimmed.isEmpty()) {
                    if (allowDuplicates || !toks.contains(trimmed)) {
                        throwIfInvalidToken(trimmed);
                        toks.add(trimmed);
                    }
                }
            }
        }
        return new TokenListHeader(toks);
    }

    private static void throwIfInvalidToken(String token) {
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (!ParseUtils.isTChar(c)) {
                throw new IllegalArgumentException("The character with code " + ((int) c) + " at position " + i + " is not a  token character");
            }
        }
    }

    /**
     * Converts the HeaderValue into a string, suitable for printing in an HTTP header.
     *
     * @return A String, such as "some-value" or "content-type:text/html;charset=UTF-8"
     */
    public String toString() {
        return String.join(", ", tokens);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TokenListHeader that = (TokenListHeader) o;
        return Objects.equals(tokens, that.tokens);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tokens);
    }


}