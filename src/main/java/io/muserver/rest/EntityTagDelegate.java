package io.muserver.rest;

import javax.ws.rs.core.EntityTag;
import javax.ws.rs.ext.RuntimeDelegate;

class EntityTagDelegate implements RuntimeDelegate.HeaderDelegate<EntityTag> {
    @Override
    public EntityTag fromString(String value) {

        if (value == null || !value.endsWith("\"")) {
            throw new IllegalArgumentException("Not a valid etag value");
        }

        if (value.startsWith("\"")) {
            return new EntityTag(unquote(value.substring(1, value.length() -1)), false);
        } else if (value.startsWith("W/\"")) {
            return new EntityTag(unquote(value.substring(3, value.length() -1)), true);
        }

        throw new IllegalArgumentException("Not a value etag value");
    }

    @Override
    public String toString(EntityTag value) {
        String quotedValue = quoted(value.getValue());
        return value.isWeak() ? "W/" + quotedValue : quotedValue;
    }

    private static String unquote(String val) {
        return val.replace("\\\"", "\"" );
    }

    private static String quoted(String val) {
        return "\"" + val.replace("\"", "\\\"") + "\"";
    }
}
