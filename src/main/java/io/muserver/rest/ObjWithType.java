package io.muserver.rest;

import jakarta.ws.rs.core.GenericEntity;
import jakarta.ws.rs.core.Response;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Type;

class ObjWithType {
    private static final ObjWithType EMPTY = new ObjWithType(null, null, null, null);

    final @Nullable Class type;
    final @Nullable Type genericType;
    final @Nullable JaxRSResponse response;
    final @Nullable Object entity;

    ObjWithType(@Nullable Class type, @Nullable Type genericType, @Nullable JaxRSResponse response, @Nullable Object entity) {
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

    static ObjWithType objType(@Nullable Object valueFromMethod) {
        return objType(valueFromMethod, null);
    }

    static ObjWithType objType(@Nullable Object valueFromMethod, @Nullable Type resourceMethodReturnType) {
        if (valueFromMethod == null) {
            return EMPTY;
        }
        @Nullable Object entity;
        @Nullable JaxRSResponse response;
        if (valueFromMethod instanceof Response) {
            response = JaxRSResponse.from((Response) valueFromMethod);
            entity = response.getEntity();
        } else {
            response = null;
            entity = valueFromMethod;
        }
        @Nullable Class type;
        @Nullable Type genericType;
        if (entity instanceof GenericEntity) {
            GenericEntity ge = (GenericEntity) entity;
            entity = ge.getEntity();
            type = ge.getRawType();
            genericType = ge.getType();
        } else {
            type = entity == null ? null : entity.getClass();
            genericType = response == null && resourceMethodReturnType != null ? resourceMethodReturnType : type;
        }
        return new ObjWithType(type, genericType, response, entity);
    }
}
