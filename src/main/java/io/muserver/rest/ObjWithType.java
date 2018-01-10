package io.muserver.rest;

import javax.ws.rs.core.GenericEntity;
import java.lang.reflect.Type;

class ObjWithType {
    final Class type;
    final Type genericType;
    final Object obj;

    private ObjWithType(Class type, Type genericType, Object obj) {
        this.type = type;
        this.genericType = genericType;
        this.obj = obj;
    }
    static ObjWithType objType(Object result) {
        Class type = result.getClass();
        Type genericType = null;
        Object obj = result;
        if (result instanceof GenericEntity) {
            GenericEntity ge = (GenericEntity) result;
            obj = ge.getEntity();
            type = ge.getRawType();
            genericType = ge.getType();
        }
        if (genericType == null) genericType = type;
        return new ObjWithType(type, genericType, obj);
    }
}
