package io.muserver.rest;

import javax.ws.rs.core.GenericEntity;
import javax.ws.rs.core.Response;
import java.lang.reflect.Type;

class LegacyObjWithType {
    private static final LegacyObjWithType EMPTY = new LegacyObjWithType(null, null, null, null);

    final Class type;
    final Type genericType;
    final LegacyJaxRSResponse response;
    final Object entity;

    LegacyObjWithType(Class type, Type genericType, LegacyJaxRSResponse response, Object entity) {
        this.type = type;
        this.genericType = genericType;
        this.response = response;
        this.entity = entity;
    }

    public int status() {
        if (response == null) {
            if (entity == null) {
                return 204;
            } else {
                return 200;
            }
        } else {
            return response.getStatus();
        }
    }

    static LegacyObjWithType objType(Object valueFromMethod) {
        if (valueFromMethod == null) {
            return EMPTY;
        }
        Object entity;
        LegacyJaxRSResponse response;
        if (valueFromMethod instanceof Response) {
            response = (LegacyJaxRSResponse)valueFromMethod;
            entity = response.getEntity();
        } else {
            response = null;
            entity = valueFromMethod;
        }
        Class type;
        Type genericType;
        if (entity instanceof GenericEntity) {
            GenericEntity ge = (GenericEntity) entity;
            entity = ge.getEntity();
            type = ge.getRawType();
            genericType = ge.getType();
        } else {
            type = entity == null ? null : entity.getClass();
            genericType = type;
        }
        return new LegacyObjWithType(type, genericType, response, entity);
    }
}
