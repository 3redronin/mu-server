package io.muserver.rest;

import java.lang.annotation.*;

/** Documents a parsed server-sent event. This annotation does not change streaming behavior. */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(ApiSseEvents.class)
public @interface ApiSseEvent {
    /** @return the event name, or empty for an unnamed event */
    String name() default "";
    /** @return the Java payload type before SSE string serialization */
    Class<?> data() default String.class;
    /** @return the media type of the serialized data field */
    String mediaType() default "text/plain";
    /** @return the event description */
    String description() default "";
    /** @return the response status code */
    String code() default "200";
}
