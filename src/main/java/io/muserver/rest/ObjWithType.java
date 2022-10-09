package io.muserver.rest;

import io.muserver.HeaderNames;
import jakarta.ws.rs.core.GenericEntity;
import jakarta.ws.rs.core.Link;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.RuntimeDelegate;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;

import static io.muserver.rest.LegacyMuRuntimeDelegate.toJakarta;

class ObjWithType {
    private static final ObjWithType EMPTY = new ObjWithType(null, null, null, null);

    final Class type;
    final Type genericType;
    final JaxRSResponse response;
    final Object entity;

    ObjWithType(Class type, Type genericType, JaxRSResponse response, Object entity) {
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
            response = (JaxRSResponse) valueFromMethod;
            entity = response.getEntity();
        } else if (valueFromMethod instanceof LegacyJavaxRSResponse) {
            LegacyJavaxRSResponse oldResp = (LegacyJavaxRSResponse) valueFromMethod;
            javax.ws.rs.core.Response.StatusType statusInfo = oldResp.getStatusInfo();
            RuntimeDelegate.HeaderDelegate<NewCookie> newCookieHeaderDelegate = RuntimeDelegate.getInstance().createHeaderDelegate(NewCookie.class);
            RuntimeDelegate.HeaderDelegate<Link> linkHeaderDelegate = RuntimeDelegate.getInstance().createHeaderDelegate(Link.class);
            Response.ResponseBuilder rb = new JaxRSResponse.Builder()
                .status(statusInfo.getStatusCode(), statusInfo.getReasonPhrase())
                .entity(oldResp.getEntity(), oldResp.getEntityAnnotations())
                .cookie(oldResp.getCookies().values().stream().map(jaxCookie -> newCookieHeaderDelegate.fromString(jaxCookie.toString())).toArray(NewCookie[]::new))
                .type(toJakarta(oldResp.getMediaType()))
                .links(oldResp.getLinks().stream().map(oldLink -> linkHeaderDelegate.fromString(oldLink.toString())).toArray(Link[]::new));
            for (Map.Entry<String, List<Object>> header : oldResp.getHeaders().entrySet()) {
                String name = header.getKey();
                if (!name.equalsIgnoreCase(HeaderNames.LINK.toString()) && !name.equalsIgnoreCase(HeaderNames.CONTENT_TYPE.toString())) {
                    rb.header(name, header.getValue());
                }
            }
            response = (JaxRSResponse) rb.build();
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
