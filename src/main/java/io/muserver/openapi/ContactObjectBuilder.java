package io.muserver.openapi;

import java.util.Map;

import org.jspecify.annotations.Nullable;

import java.net.URI;

/**
 * Contact information for the exposed API.
 */
public class ContactObjectBuilder {
    private @Nullable Map<String, Object> extensions;
    private @Nullable String name;
    private @Nullable URI url;
    private @Nullable String email;

    /**
     *
     * @param name The identifying name of the contact person/organization.
     *
     * @return The current builder
     */
    public ContactObjectBuilder withName(@Nullable String name) {
        this.name = name;
        return this;
    }

    /**
     *
     * @param url The URL pointing to the contact information.
     *
     * @return The current builder
     */
    public ContactObjectBuilder withUrl(@Nullable URI url) {
        this.url = url;
        return this;
    }

    /**
     *
     * @param email The email address of the contact person/organization. MUST be in the format of an email address.
     *
     * @return The current builder
     */
    public ContactObjectBuilder withEmail(@Nullable String email) {
        this.email = email;
        return this;
    }

    /**
     * @return A new object
     */
    public ContactObject build() {
        return new ContactObject(name, url, email, extensions);
    }

    /**
     * Creates a builder for a {@link ContactObject}
     *
     * @return A new builder
     */
    public static ContactObjectBuilder contactObject() {
        return new ContactObjectBuilder();
    }
    /**
     * @param value the extensions value
     * @return this builder */
    public ContactObjectBuilder withExtensions(@Nullable Map<String, Object> value) { this.extensions = value; return this; }
    /**
     * @param name an x- extension name
     * @param value its JSON value
     * @return this builder */
    public ContactObjectBuilder withExtension(String name, @Nullable Object value) {
        Map<String, Object> copy = new java.util.LinkedHashMap<>();
        if (extensions != null) copy.putAll(extensions);
        Extensions.put(copy, name, value);
        extensions = copy;
        return this;
    }
}
