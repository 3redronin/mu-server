package io.muserver.rest;

import javax.ws.rs.core.GenericEntity;
import javax.ws.rs.core.Response;
import java.lang.reflect.Type;

class ObjWithType {
    private static final ObjWithType EMPTY = new ObjWithType(null, null, null, null);

    final Class type;
    final Type genericType;
    final JaxRSResponse response;
    final Object entity;

    private ObjWithType(Class type, Type genericType, JaxRSResponse response, Object entity) {
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

    static ObjWithType objType(Object valueFromMethod) {
        if (valueFromMethod == null) {
            return EMPTY;
        }
        Object entity;
        JaxRSResponse response;
        if (valueFromMethod instanceof Response) {
            response = (JaxRSResponse)valueFromMethod;
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
        return new ObjWithType(type, genericType, response, entity);
    }
}
