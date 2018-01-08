/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2010-2015 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * http://glassfish.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
/*
NOTE: This file uses portions of code from the Jersey JAX-RS Spec:
https://github.com/jersey/jersey/blob/12e5d8bdf22bcd2676a1032ed69473cf2bbc48c7/core-common/src/main/java/org/glassfish/jersey/message/internal/CacheControlProvider.java
 */
package io.muserver.rest;

import javax.ws.rs.core.CacheControl;
import javax.ws.rs.ext.RuntimeDelegate;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class CacheControlHeaderDelegate implements RuntimeDelegate.HeaderDelegate<CacheControl> {
    private static final Pattern WHITESPACE = Pattern.compile("\\s");

    @Override
    public CacheControl fromString(String value) {
        throw NotImplementedException.notYet();
    }

    @Override
    public String toString(CacheControl value) {
        StringBuilder sb = new StringBuilder();
        if (value.isPrivate()) {
            appendQuotedWithSeparator(sb, "private", buildListValue(value.getPrivateFields()));
        }
        if (value.isNoCache()) {
            appendQuotedWithSeparator(sb, "no-cache", buildListValue(value.getNoCacheFields()));
        }
        if (value.isNoStore()) {
            appendWithSeparator(sb, "no-store");
        }
        if (value.isNoTransform()) {
            appendWithSeparator(sb, "no-transform");
        }
        if (value.isMustRevalidate()) {
            appendWithSeparator(sb, "must-revalidate");
        }
        if (value.isProxyRevalidate()) {
            appendWithSeparator(sb, "proxy-revalidate");
        }
        if (value.getMaxAge() != -1) {
            appendWithSeparator(sb, "max-age", value.getMaxAge());
        }
        if (value.getSMaxAge() != -1) {
            appendWithSeparator(sb, "s-maxage", value.getSMaxAge());
        }

        for (Map.Entry<String, String> e : value.getCacheExtension().entrySet()) {
            String val = e.getValue();
            appendWithSeparator(sb, e.getKey(), quoteIfWhitespace(val));
        }

        return sb.toString();
    }

    private String buildListValue(List<String> values) {
        StringBuilder b = new StringBuilder();
        for (String value : values) {
            appendWithSeparator(b, value);
        }
        return b.toString();
    }

    private void appendWithSeparator(StringBuilder b, String field) {
        if (b.length() > 0) {
            b.append(", ");
        }
        b.append(field);
    }

    private void appendQuotedWithSeparator(StringBuilder b, String field, String value) {
        appendWithSeparator(b, field);
        if (value != null && !value.isEmpty()) {
            b.append("=\"");
            b.append(value);
            b.append("\"");
        }
    }

    private void appendWithSeparator(StringBuilder b, String field, int value) {
        appendWithSeparator(b, field);
        b.append("=");
        b.append(value);
    }

    private void appendWithSeparator(StringBuilder b, String field, String value) {
        appendWithSeparator(b, field);
        if (value != null && !value.isEmpty()) {
            b.append("=");
            b.append(value);
        }
    }

    private String quoteIfWhitespace(String value) {
        if (value == null) {
            return null;
        }
        Matcher m = WHITESPACE.matcher(value);
        if (m.find()) {
            return "\"" + value + "\"";
        }
        return value;
    }
}
