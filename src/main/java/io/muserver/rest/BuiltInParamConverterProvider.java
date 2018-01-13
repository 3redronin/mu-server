package io.muserver.rest;

import javax.ws.rs.ext.ParamConverter;
import javax.ws.rs.ext.ParamConverterProvider;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;

class BuiltInParamConverterProvider implements ParamConverterProvider {

    private final ParamConverter<String> stringParamConverter = new ParamConverter<String>() {
        public String fromString(String value) {
            return value;
        }
        public String toString(String value) {
            return value;
        }
    };


    @Override
    public <T> ParamConverter getConverter(Class<T> rawType, Type genericType, Annotation[] annotations) {
        if (String.class.isAssignableFrom(rawType)) {
            return stringParamConverter;
        }
        return null;
    }

}
