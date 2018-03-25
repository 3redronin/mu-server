package io.muserver.openapi;

import java.net.URI;

/**
 * Contact information for the exposed API.
 */
public class ContactObjectBuilder {
    private String name;
    private URI url;
    private String email;

    /**
     * @param name The identifying name of the contact person/organization.
     * @return The current builder
     */
    public ContactObjectBuilder withName(String name) {
        this.name = name;
        return this;
    }

    /**
     * @param url The URL pointing to the contact information.
     * @return The current builder
     */
    public ContactObjectBuilder withUrl(URI url) {
        this.url = url;
        return this;
    }

    /**
     * @param email The email address of the contact person/organization. MUST be in the format of an email address.
     * @return The current builder
     */
    public ContactObjectBuilder withEmail(String email) {
        this.email = email;
        return this;
    }

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