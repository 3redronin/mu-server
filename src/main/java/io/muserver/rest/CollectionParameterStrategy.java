package io.muserver.rest;

/**
 * Specifies how to handle lists or sets in querystring parameters
 * <p>This can be used to allow clients to send lists of values in comma-separated values to query string parameters in JAX-RS methods.</p>
 */
public enum CollectionParameterStrategy {

    /**
     * Splits parameter values on commas, for example <code>a,b,c</code> would result in a list of 3 strings.
     * <p>With this option enabled, values are trimmed and empty values are removed, so <code>a,%20b,</code> would be a list with 2 values (&quot;a&quot; and &quot;b&quot;).</p>
     */
    SPLIT_ON_COMMA,

    /**
     * No transformation is applied, for example <code>a,b,c</code> would result in a single string with value &quot;a,b,c&quot;
     * <p>This option follows the JAX-RS standard.</p>
     */
    NO_TRANSFORM

}
