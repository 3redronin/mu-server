package io.muserver.rest;

import javax.ws.rs.core.MediaType;
import java.util.Objects;

/**
 * As described by the ordering section in 3.7.2 part 3.b
 */
class CombinedMediaType implements Comparable<CombinedMediaType> {

    public static CombinedMediaType NONMATCH = new CombinedMediaType(null, null, 1.0, 1.0, 0, null);

    public final String type;
    public final String subType;
    public final double q;
    public final double qs;
    public final int d;
    public final boolean isWildcardType;
    public final boolean isWildcardSubtype;
    public final String charset;

    CombinedMediaType(String type, String subType, double q, double qs, int d, String charset) {
        this.type = type;
        this.subType = subType;
        this.q = q;
        this.qs = qs;
        this.d = d;
        this.isWildcardType = "*".equals(type);
        this.isWildcardSubtype = "*".equals(subType);
        this.charset = charset;
    }

    public boolean isConcrete() {
        return !isWildcardType && !isWildcardSubtype;
    }

    public static CombinedMediaType s(MediaType clientType, MediaType serverType) {
        if (!clientType.isCompatible(serverType)) {
            return NONMATCH;
        }
        String type = clientType.isWildcardType() ? serverType.getType() : clientType.getType();
        String sub = clientType.isWildcardSubtype() ? serverType.getSubtype() : clientType.getSubtype();
        double q = Double.parseDouble(clientType.getParameters().getOrDefault("q", "1.0"));
        double qs = Double.parseDouble(serverType.getParameters().getOrDefault("qs", "1.0"));
        int d = 0;
        if (clientType.isWildcardType() ^ serverType.isWildcardType()) {
            d++;
        }
        if (clientType.isWildcardSubtype() ^ serverType.isWildcardSubtype()) {
            d++;
        }
        return new CombinedMediaType(type, sub, q, qs, d, serverType.getParameters().get("charset"));
    }

    @Override
    public int compareTo(CombinedMediaType o) {
        if (isWildcardType ^ o.isWildcardType) {
            return Boolean.compare(o.isWildcardType, this.isWildcardType);
        }
        if (isWildcardSubtype ^ o.isWildcardSubtype) {
            return Boolean.compare(o.isWildcardSubtype, this.isWildcardSubtype);
        }
        int qVal = Double.compare(this.q, o.q);
        if (qVal != 0) {
            return qVal;
        }
        int qsVal = Double.compare(this.qs, o.qs);
        if (qsVal != 0) {
            return qsVal;
        }
        return Integer.compare(o.d, this.d);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CombinedMediaType that = (CombinedMediaType) o;
        return Double.compare(that.q, q) == 0 &&
            Double.compare(that.qs, qs) == 0 &&
            d == that.d &&
            Objects.equals(type, that.type) &&
            Objects.equals(subType, that.subType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, subType, q, qs, d);
    }

    @Override
    public String toString() {
        return "CombinedMediaType{" +
            "type='" + type + '\'' +
            ", subType='" + subType + '\'' +
            ", q=" + q +
            ", qs=" + qs +
            ", d=" + d +
            '}';
    }
}
