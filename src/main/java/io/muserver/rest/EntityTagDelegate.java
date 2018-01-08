package io.muserver.rest;

import javax.ws.rs.core.EntityTag;
import javax.ws.rs.ext.RuntimeDelegate;

class EntityTagDelegate implements RuntimeDelegate.HeaderDelegate<EntityTag> {
    @Override
    public EntityTag fromString(String value) {
        throw NotImplementedException.notYet();
    }

    @Override
    public String toString(EntityTag value) {
        String quotedValue = quoted(value.getValue());
        return value.isWeak() ? "W/" + quotedValue : quotedValue;
    }

    private static String quoted(String val) {
        return "\"" + val.replace("\"", "\\\"") + "\"";
    }
}
