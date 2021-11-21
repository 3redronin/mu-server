package io.muserver.rest;

import io.muserver.ParameterizedHeaderWithValue;
import org.junit.Test;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Variant;
import java.util.List;
import java.util.Locale;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class MuVariantListBuilderTest {
    static {
        MuRuntimeDelegate.ensureSet();
    }
    private final Variant.VariantListBuilder builder = MuRuntimeDelegate.getInstance().createVariantListBuilder();

    @Test
    public void emptyIsOkay() {
        assertThat(builder.build(), is(empty()));
    }

    @Test(expected = IllegalStateException.class)
    public void throwsIfNothingToAdd() {
        builder.add();
    }

    @Test
    public void callingAddAtEndIsOptional() {
        List<Variant> variants = Variant.VariantListBuilder.newInstance()
            .languages(Locale.ENGLISH, Locale.FRENCH).encodings("zip", "identity").add()
            .languages(Locale.GERMAN).mediaTypes(MediaType.TEXT_PLAIN_TYPE)
            .build();
        System.out.println("variants = " + variants);
        assertThat(variants, hasSize(5));
    }

    @Test
    public void itCanSelectTheBestVariant() {
        List<Variant> availableVariants = Variant.VariantListBuilder.newInstance()
            .mediaTypes(MediaType.APPLICATION_XML_TYPE, MediaType.APPLICATION_JSON_TYPE)
            .languages(new Locale("en", "NZ"), new Locale("es"))
            .encodings("deflate", "gzip").build();
        assertThat(MuVariantListBuilder.selectVariant(availableVariants, acceptLang("en;q=0.5, zh-CN, es-SP"), asList(MediaType.APPLICATION_JSON_TYPE, MediaType.APPLICATION_FORM_URLENCODED_TYPE), ParameterizedHeaderWithValue.fromString("gzip, pkunzip")), equalTo(new Variant(MediaType.APPLICATION_JSON_TYPE, "es", "gzip")));
        assertThat(MuVariantListBuilder.selectVariant(availableVariants, acceptLang("en-US,en;q=0.5, zh-CN"), asList(MediaType.APPLICATION_JSON_TYPE, MediaType.APPLICATION_FORM_URLENCODED_TYPE), ParameterizedHeaderWithValue.fromString("gzip, pkunzip")), equalTo(new Variant(MediaType.APPLICATION_JSON_TYPE, "en", "NZ", "gzip")));
        assertThat(MuVariantListBuilder.selectVariant(availableVariants, acceptLang("en, zh-CN"), asList(MediaType.APPLICATION_JSON_TYPE, MediaType.APPLICATION_FORM_URLENCODED_TYPE), ParameterizedHeaderWithValue.fromString("gzip, pkunzip")), equalTo(new Variant(MediaType.APPLICATION_JSON_TYPE, "en", "NZ", "gzip")));
        assertThat(MuVariantListBuilder.selectVariant(availableVariants, acceptLang("en, zh-CN"), asList(MediaType.APPLICATION_JSON_TYPE, MediaType.APPLICATION_FORM_URLENCODED_TYPE), ParameterizedHeaderWithValue.fromString("pkunzip")), nullValue());
        assertThat(MuVariantListBuilder.selectVariant(availableVariants, acceptLang("en, zh-CN"), asList(MediaType.APPLICATION_FORM_URLENCODED_TYPE), ParameterizedHeaderWithValue.fromString("gzip, pkunzip")), nullValue());
        assertThat(MuVariantListBuilder.selectVariant(availableVariants, acceptLang("zh-CN"), asList(MediaType.APPLICATION_JSON_TYPE, MediaType.APPLICATION_FORM_URLENCODED_TYPE), ParameterizedHeaderWithValue.fromString("gzip, pkunzip")), nullValue());
    }

    @Test
    public void itCanSelectTheBestVariantIgnoringMediaTypes() {
        List<Variant> availableVariants = Variant.VariantListBuilder.newInstance()
            .languages(new Locale("en"), new Locale("es"))
            .encodings("deflate", "gzip").build();
        assertThat(MuVariantListBuilder.selectVariant(availableVariants, acceptLang("en, zh-CN"), asList(MediaType.APPLICATION_JSON_TYPE, MediaType.APPLICATION_FORM_URLENCODED_TYPE), ParameterizedHeaderWithValue.fromString("gzip, pkunzip")), equalTo(new Variant(null, "en", "gzip")));
        assertThat(MuVariantListBuilder.selectVariant(availableVariants, acceptLang("en, zh-CN"), asList(MediaType.APPLICATION_JSON_TYPE, MediaType.APPLICATION_FORM_URLENCODED_TYPE), ParameterizedHeaderWithValue.fromString("pkunzip")), nullValue());
        assertThat(MuVariantListBuilder.selectVariant(availableVariants, acceptLang("en, zh-CN"), emptyList(), ParameterizedHeaderWithValue.fromString("gzip, pkunzip")), equalTo(new Variant(null, "en", "gzip")));
        assertThat(MuVariantListBuilder.selectVariant(availableVariants, acceptLang("zh-CN"), asList(MediaType.APPLICATION_JSON_TYPE, MediaType.APPLICATION_FORM_URLENCODED_TYPE), ParameterizedHeaderWithValue.fromString("gzip, pkunzip")), nullValue());
    }

    @Test
    public void itCanSelectTheBestVariantIgnoringEncodings() {
        List<Variant> availableVariants = Variant.VariantListBuilder.newInstance()
            .mediaTypes(MediaType.APPLICATION_XML_TYPE, MediaType.APPLICATION_JSON_TYPE)
            .languages(new Locale("en"), new Locale("es"))
            .build();
        assertThat(MuVariantListBuilder.selectVariant(availableVariants, acceptLang("en, zh-CN"), asList(MediaType.APPLICATION_JSON_TYPE, MediaType.APPLICATION_FORM_URLENCODED_TYPE), ParameterizedHeaderWithValue.fromString("gzip, pkunzip")), equalTo(new Variant(MediaType.APPLICATION_JSON_TYPE, "en", null)));
        assertThat(MuVariantListBuilder.selectVariant(availableVariants, acceptLang("en, zh-CN"), asList(MediaType.APPLICATION_JSON_TYPE, MediaType.APPLICATION_FORM_URLENCODED_TYPE), emptyList()), equalTo(new Variant(MediaType.APPLICATION_JSON_TYPE, "en", null)));
        assertThat(MuVariantListBuilder.selectVariant(availableVariants, acceptLang("en, zh-CN"), asList(MediaType.APPLICATION_FORM_URLENCODED_TYPE), ParameterizedHeaderWithValue.fromString("gzip, pkunzip")), nullValue());
        assertThat(MuVariantListBuilder.selectVariant(availableVariants, acceptLang("zh-CN"), asList(MediaType.APPLICATION_JSON_TYPE, MediaType.APPLICATION_FORM_URLENCODED_TYPE), ParameterizedHeaderWithValue.fromString("gzip, pkunzip")), nullValue());
    }

    @Test
    public void itCanSelectTheBestVariantIgnoringLanguage() {
        List<Variant> availableVariants = Variant.VariantListBuilder.newInstance()
            .mediaTypes(MediaType.APPLICATION_XML_TYPE, MediaType.APPLICATION_JSON_TYPE)
            .encodings("deflate", "gzip").build();
        assertThat(MuVariantListBuilder.selectVariant(availableVariants, acceptLang("en, zh-CN"), asList(MediaType.APPLICATION_JSON_TYPE, MediaType.APPLICATION_FORM_URLENCODED_TYPE), ParameterizedHeaderWithValue.fromString("gzip, pkunzip")), equalTo(new Variant(MediaType.APPLICATION_JSON_TYPE, (Locale)null, "gzip")));
        assertThat(MuVariantListBuilder.selectVariant(availableVariants, acceptLang("en, zh-CN"), asList(MediaType.APPLICATION_JSON_TYPE, MediaType.APPLICATION_FORM_URLENCODED_TYPE), ParameterizedHeaderWithValue.fromString("pkunzip")), nullValue());
        assertThat(MuVariantListBuilder.selectVariant(availableVariants, acceptLang("en, zh-CN"), asList(MediaType.APPLICATION_FORM_URLENCODED_TYPE), ParameterizedHeaderWithValue.fromString("gzip, pkunzip")), nullValue());
        assertThat(MuVariantListBuilder.selectVariant(availableVariants, acceptLang("*"), asList(MediaType.APPLICATION_JSON_TYPE, MediaType.APPLICATION_FORM_URLENCODED_TYPE), ParameterizedHeaderWithValue.fromString("gzip, pkunzip")), equalTo(new Variant(MediaType.APPLICATION_JSON_TYPE, (Locale)null, "gzip")));
    }

    List<Locale.LanguageRange> acceptLang(String s) {
        return Locale.LanguageRange.parse(s);
    }


}