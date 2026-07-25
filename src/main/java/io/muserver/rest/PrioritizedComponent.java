package io.muserver.rest;

import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;

interface PrioritizedComponent {

    int priority();

    static int priorityOf(Object component) {
        if (component instanceof PrioritizedComponent) {
            return ((PrioritizedComponent) component).priority();
        }
        Priority priority = component.getClass().getAnnotation(Priority.class);
        return priority == null ? Priorities.USER : priority.value();
    }
}
