package io.muserver.rest;

/**
 * Specifies how to handle collection or array query-string parameters.
 * <p>This can be used to allow clients to send multiple values in a comma-separated query-string parameter to JAX-RS methods.</p>
 */
public enum CollectionParameterStrategy {

    /**
     * Splits parameter values on commas, for example <code>a,b,c</code> produces 3 strings.
     * <p>With this option enabled, values are trimmed and empty values are removed, so <code>a,%20b,</code> produces 2 values (&quot;a&quot; and &quot;b&quot;).</p>
     */
    SPLIT_ON_COMMA,

    /**
     * No transformation is applied, for example <code>a,b,c</code> would result in a single string with value &quot;a,b,c&quot;
     * <p>This option follows the JAX-RS standard.</p>
     */
    NO_TRANSFORM

}
