package io.muserver.rest;

import io.muserver.ParameterizedHeaderWithValue;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Variant;
import java.util.*;

import static java.util.Arrays.asList;
import static java.util.Collections.singleton;
import static java.util.Collections.singletonList;

class MuVariantListBuilder extends Variant.VariantListBuilder {

    private final List<Locale> languages = new ArrayList<>();
    private final List<String> encodings = new ArrayList<>();
    private final List<MediaType> mediaTypes = new ArrayList<>();
    private final List<Variant> variants = new ArrayList<>();

    @Override
    public List<Variant> build() {
        if (addPending()) {
            add();
        }
        List<Variant> temp = new ArrayList<>(variants);
        variants.clear();
        return temp;
    }

    private boolean addPending() {
        return !languages.isEmpty() || !encodings.isEmpty() || !mediaTypes.isEmpty();
    }

    @Override
    public Variant.VariantListBuilder add() {
        if (!addPending()) {
            throw new IllegalStateException("No variants to add");
        }
        List<MediaType> mts = this.mediaTypes.isEmpty() ? singletonList(null) : this.mediaTypes;
        List<Locale> langs = this.languages.isEmpty() ? singletonList(null) : this.languages;
        List<String> encs = this.encodings.isEmpty() ? singletonList(null) : this.encodings;
        for (String enc : encs) {
            for (Locale lang : langs) {
                for (MediaType mt : mts) {
                    this.variants.add(new Variant(mt, lang, enc));
                }
            }
        }
        this.mediaTypes.clear();
        this.languages.clear();
        this.encodings.clear();
        return this;
    }

    @Override
    public Variant.VariantListBuilder languages(Locale... languages) {
        this.languages.addAll(asList(languages));
        return this;
    }

    @Override
    public Variant.VariantListBuilder encodings(String... encodings) {
        this.encodings.addAll(asList(encodings));
        return this;
    }

    @Override
    public Variant.VariantListBuilder mediaTypes(MediaType... mediaTypes) {
        this.mediaTypes.addAll(asList(mediaTypes));
        return this;
    }

    static Variant selectVariant(List<Variant> available, List<Locale.LanguageRange> preferredLanguages, List<MediaType> acceptableMediaTypes, List<ParameterizedHeaderWithValue> acceptableEncodings) {
        Locale.LanguageRange wildcardRanger = new Locale.LanguageRange("*");

        List<Variant> candidates = new ArrayList<>();
        for (Variant candidate : available) {
            MediaType cmt = candidate.getMediaType();
            boolean mtOkay = cmt == null || MediaTypeHeaderDelegate.atLeastOneCompatible(acceptableMediaTypes, singletonList(cmt));

            String cenc = candidate.getEncoding();
            boolean encOkay = cenc == null || acceptableEncodings.stream().anyMatch(ae -> ae.value().equals(cenc));

            Locale clang = candidate.getLanguage();
            boolean langOk = clang == null || (Locale.lookup(preferredLanguages, singleton(clang)) != null) || preferredLanguages.contains(wildcardRanger);

            if (langOk && encOkay && mtOkay) {
                candidates.add(candidate);
            }
        }
        return candidates.stream().min((v1, v2) -> {
            // Prioritise matching of languages first.
            // If one has a language specified, it wins
            int langCompare = Boolean.compare(v2.getLanguage() == null, v1.getLanguage() == null);
            if (langCompare == 0 && v1.getLanguage() != null) {
                // Both have languages, so figure out which one has a better match
                Locale bestLang = Locale.lookup(preferredLanguages, asList(v1.getLanguage(), v2.getLanguage()));
                langCompare = Boolean.compare(Objects.equals(bestLang, v2.getLanguage()), Objects.equals(bestLang, v1.getLanguage()));
            }
            if (langCompare != 0) {
                return langCompare;
            }

            // Languages are the same - now match on media type
            int mtCompare = Boolean.compare(v2.getMediaType() != null, v1.getMediaType() != null);
            if (mtCompare == 0 && v1.getMediaType() != null) {
                // Now find the one with best q-value
                MediaType bestMT = bestMediaType(acceptableMediaTypes, asList(v1.getMediaType(), v2.getMediaType()));
                mtCompare = Boolean.compare(Objects.equals(bestMT, v2.getMediaType()), Objects.equals(bestMT, v1.getMediaType()));
            }
            if (mtCompare != 0) {
                return mtCompare;
            }

            // Finally check the encoding options. If one has an encoding, use it
            int encCompare = Boolean.compare(v2.getEncoding() != null, v1.getEncoding() != null);
            if (encCompare == 0 && v1.getEncoding() != null) {
                // Now find the one with best q-value
                encCompare = Double.compare(bestEncodingScore(acceptableEncodings, v2), bestEncodingScore(acceptableEncodings, v1));
            }
            return encCompare;
        }).orElse(null);

    }

    private static MediaType bestMediaType(List<MediaType> acceptableMediaTypes, Collection<MediaType> candidates) {
        return acceptableMediaTypes.stream().filter(amt -> candidates.stream().anyMatch(c -> c.isCompatible(amt)))
            .min(Comparator.comparing(MuVariantListBuilder::mtqScore)
            .thenComparing((o1, o2) -> Boolean.compare(candidates.contains(o2), candidates.contains(o1)))
            .thenComparing((o1, o2) -> Boolean.compare(o1.isWildcardType(), o2.isWildcardType()))
            .thenComparing((o1, o2) -> Boolean.compare(o1.isWildcardSubtype(), o2.isWildcardSubtype()))).orElse(null);
    }

    private static double bestEncodingScore(List<ParameterizedHeaderWithValue> acceptableEncodings, Variant variant) {
        return acceptableEncodings.stream().filter(enc -> variant.getEncoding().equals(enc.value())).min((o1, o2) -> Double.compare(qScore(o2), qScore(o1))).map(MuVariantListBuilder::qScore).orElse(1.0);
    }

    private static double qScore(ParameterizedHeaderWithValue p) {
        return Double.parseDouble(p.parameter("q", "1.0"));
    }

    private static double mtqScore(MediaType mediaType) {
        return Double.parseDouble(mediaType.getParameters().getOrDefault("q", "1.0"));
    }

}
