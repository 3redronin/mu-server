package io.muserver.rest;

import io.muserver.Mutils;

import javax.ws.rs.ext.RuntimeDelegate;
import java.time.format.DateTimeParseException;
import java.util.Date;

class DateHeaderDelegate implements RuntimeDelegate.HeaderDelegate<Date> {

    @Override
    public Date fromString(String value) {
        try {
            return Mutils.fromHttpDate(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
    }

    @Override
    public String toString(Date value) {
        return Mutils.toHttpDate(value);
    }
}
