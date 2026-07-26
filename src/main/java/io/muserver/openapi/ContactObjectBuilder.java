package io.muserver.openapi;

import org.jspecify.annotations.Nullable;

import java.net.URI;

/**
 * Contact information for the exposed API.
 */
public class ContactObjectBuilder {
    private @Nullable String name;
    private @Nullable URI url;
    private @Nullable String email;

    /**
     * Creates an empty contact object builder.
     */
    public ContactObjectBuilder() {
    }

    /**
     * Sets the contact name.
     *
     * @param name The identifying name of the contact person/organization.
     * @return The current builder
     */
    public ContactObjectBuilder withName(@Nullable String name) {
        this.name = name;
        return this;
    }

    /**
     * Sets the contact URL.
     *
     * @param url The URL pointing to the contact information.
     * @return The current builder
     */
    public ContactObjectBuilder withUrl(@Nullable URI url) {
        this.url = url;
        return this;
    }

    /**
     * Sets the contact email address.
     *
     * @param email The email address of the contact person/organization. MUST be in the format of an email address.
     * @return The current builder
     */
    public ContactObjectBuilder withEmail(@Nullable String email) {
        this.email = email;
        return this;
    }

    /**
     * Creates the configured contact object.
     *
     * @return A new object
     */
    public ContactObject build() {
        return new ContactObject(name, url, email);
    }

    /**
     * Creates a builder for a {@link ContactObject}
     *
     * @return A new builder
     */
    public static ContactObjectBuilder contactObject() {
        return new ContactObjectBuilder();
    }
}