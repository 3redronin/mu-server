package io.muserver.rest;

import io.muserver.ParameterizedHeaderWithValue;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Variant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static java.util.Arrays.asList;
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

    static Variant selectVariant(List<Variant> available, List<Locale.LanguageRange> acceptableLanguages, List<MediaType> acceptableMediaTypes, List<ParameterizedHeaderWithValue> acceptableEncodings) {

        List<Variant> candidates = new ArrayList<>();
        for (Variant candidate : available) {
            boolean mtOkay = true;
            MediaType cmt = candidate.getMediaType();
            if (cmt != null) {
                mtOkay = acceptableMediaTypes.stream().anyMatch(amt -> amt.isCompatible(cmt));
            }

            String cenc = candidate.getEncoding();
            boolean encOkay = cenc == null || acceptableEncodings.stream().anyMatch(ae -> ae.value().equals(cenc));

            Locale clang = candidate.getLanguage();
            boolean langOk = true;
            if (clang != null) {
                // from https://www.w3.org/Protocols/rfc2616/rfc2616-sec14.html
                // A language-range matches a language-tag if it exactly equals the tag, or if it exactly equals a
                // prefix of the tag such that the first tag character following the prefix is "-". The special
                // range "*", if present in the Accept-Language field, matches every tag not matched by any other
                // range present in the Accept-Language field.
                String availableLang = clang.toLanguageTag();
                langOk = availableLang.equals("*") || acceptableLanguages.stream().anyMatch(acceptedLang -> langMatchesRange(availableLang, acceptedLang));
            }
            if (langOk && encOkay && mtOkay) {
                candidates.add(candidate);
            }

        }
        return candidates.stream().min((v1, v2) -> {
            double v1LangScore = langScore(v1.getLanguage(), acceptableLanguages);
            double v2LangScore = langScore(v2.getLanguage(), acceptableLanguages);
            return Double.compare(v2LangScore, v1LangScore);
        }).orElse(null);

    }

    private static boolean langMatchesRange(String availableLang, Locale.LanguageRange acceptedLang) {
        String range = acceptedLang.getRange();
        if (range.equals(availableLang)) {
            return true;
        }
        if (availableLang.startsWith(range)) {
            return availableLang.length() > range.length() && availableLang.charAt(range.length()) == '-';
        }
        return false;
    }

    private static double langScore(Locale variantLang, List<Locale.LanguageRange> acceptableLanguages) {
        double score = 0.0;
        for (Locale.LanguageRange acceptableLang : acceptableLanguages) {
            if (langMatchesRange(variantLang.toLanguageTag(), acceptableLang)) {
                score = Math.max(score, acceptableLang.getWeight());
            }
        }
        return score;
    }
}
