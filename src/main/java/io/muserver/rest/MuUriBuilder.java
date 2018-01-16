package io.muserver.rest;

import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.core.UriBuilderException;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.Map;

class MuUriBuilder extends UriBuilder {
    @Override
    public UriBuilder clone() {
        throw NotImplementedException.notYet();
    }

    @Override
    public UriBuilder uri(URI uri) {
        throw NotImplementedException.notYet();
    }

    @Override
    public UriBuilder uri(String uriTemplate) {
        throw NotImplementedException.notYet();
    }

    @Override
    public UriBuilder scheme(String scheme) {
        throw NotImplementedException.notYet();
    }

    @Override
    public UriBuilder schemeSpecificPart(String ssp) {
        throw NotImplementedException.notYet();
    }

    @Override
    public UriBuilder userInfo(String ui) {
        throw NotImplementedException.notYet();
    }

    @Override
    public UriBuilder host(String host) {
        throw NotImplementedException.notYet();
    }

    @Override
    public UriBuilder port(int port) {
        throw NotImplementedException.notYet();
    }

    @Override
    public UriBuilder replacePath(String path) {
        throw NotImplementedException.notYet();
    }

    @Override
    public UriBuilder path(String path) {
        throw NotImplementedException.notYet();
    }

    @Override
    public UriBuilder path(Class resource) {
        throw NotImplementedException.notYet();
    }

    @Override
    public UriBuilder path(Class resource, String method) {
        throw NotImplementedException.notYet();
    }

    @Override
    public UriBuilder path(Method method) {
        throw NotImplementedException.notYet();
    }

    @Override
    public UriBuilder segment(String... segments) {
        throw NotImplementedException.notYet();
    }

    @Override
    public UriBuilder replaceMatrix(String matrix) {
        throw NotImplementedException.notYet();
    }

    @Override
    public UriBuilder matrixParam(String name, Object... values) {
        throw NotImplementedException.notYet();
    }

    @Override
    public UriBuilder replaceMatrixParam(String name, Object... values) {
        throw NotImplementedException.notYet();
    }

    @Override
    public UriBuilder replaceQuery(String query) {
        throw NotImplementedException.notYet();
    }

    @Override
    public UriBuilder queryParam(String name, Object... values) {
        throw NotImplementedException.notYet();
    }

    @Override
    public UriBuilder replaceQueryParam(String name, Object... values) {
        throw NotImplementedException.notYet();
    }

    @Override
    public UriBuilder fragment(String fragment) {
        throw NotImplementedException.notYet();
    }

    @Override
    public UriBuilder resolveTemplate(String name, Object value) {
        throw NotImplementedException.notYet();
    }

    @Override
    public UriBuilder resolveTemplate(String name, Object value, boolean encodeSlashInPath) {
        throw NotImplementedException.notYet();
    }

    @Override
    public UriBuilder resolveTemplateFromEncoded(String name, Object value) {
        throw NotImplementedException.notYet();
    }

    @Override
    public UriBuilder resolveTemplates(Map<String, Object> templateValues) {
        throw NotImplementedException.notYet();
    }

    @Override
    public UriBuilder resolveTemplates(Map<String, Object> templateValues, boolean encodeSlashInPath) throws IllegalArgumentException {
        throw NotImplementedException.notYet();
    }

    @Override
    public UriBuilder resolveTemplatesFromEncoded(Map<String, Object> templateValues) {
        throw NotImplementedException.notYet();
    }

    @Override
    public URI buildFromMap(Map<String, ?> values) {
        throw NotImplementedException.notYet();
    }

    @Override
    public URI buildFromMap(Map<String, ?> values, boolean encodeSlashInPath) throws IllegalArgumentException, UriBuilderException {
        throw NotImplementedException.notYet();
    }

    @Override
    public URI buildFromEncodedMap(Map<String, ?> values) throws IllegalArgumentException, UriBuilderException {
        throw NotImplementedException.notYet();
    }

    @Override
    public URI build(Object... values) throws IllegalArgumentException, UriBuilderException {
        throw NotImplementedException.notYet();
    }

    @Override
    public URI build(Object[] values, boolean encodeSlashInPath) throws IllegalArgumentException, UriBuilderException {
        throw NotImplementedException.notYet();
    }

    @Override
    public URI buildFromEncoded(Object... values) throws IllegalArgumentException, UriBuilderException {
        throw NotImplementedException.notYet();
    }

    @Override
    public String toTemplate() {
        throw NotImplementedException.notYet();
    }
}
