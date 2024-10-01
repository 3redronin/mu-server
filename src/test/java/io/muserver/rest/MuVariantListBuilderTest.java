package io.muserver.rest;

import io.muserver.ParameterizedHeaderWithValue;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Variant;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class MuVariantListBuilderTest {
    static {
        MuRuntimeDelegate.ensureSet();
    }
    private final Variant.VariantListBuilder builder = MuRuntimeDelegate.getInstance().createVariantListBuilder();

    @Test
    public void emptyIsOkay() {
        assertThat(builder.build(), is(empty()));
    }

    @Test
    public void throwsIfNothingToAdd() {
        assertThrows(IllegalStateException.class, builder::add);
    }

    @Test
    public void callingAddAtEndIsOptional() {
        List<Variant> variants = Variant.VariantListBuilder.newInstance()
            .languages(Locale.ENGLISH, Locale.FRENCH).encodings("zip", "identity").add()
            .languages(Locale.GERMAN).mediaTypes(MediaType.TEXT_PLAIN_TYPE)
            .build();
        assertThat(variants, containsInAnyOrder(
            new Variant(null, "en", "zip"),
            new Variant(null, "fr", "zip"),
            new Variant(null, "en", "identity"),
            new Variant(null, "fr", "identity"),
            new Variant(MediaType.TEXT_PLAIN_TYPE, "de", null)
        ));
    }

    @Test
    public void itCanSelectTheBestVariant() {
        List<Variant> availableVariants = Variant.VariantListBuilder.newInstance()
            .mediaTypes(MediaType.APPLICATION_XML_TYPE, MediaType.APPLICATION_JSON_TYPE)
            .languages(new Locale("en", "NZ"), new Locale("es"))
            .encodings("deflate", "gzip").build();
        assertThat(MuVariantListBuilder.selectVariant(availableVariants, acceptLang("en;q=0.5, zh-CN, es-SP"), asList(MediaType.APPLICATION_JSON_TYPE, MediaType.APPLICATION_FORM_URLENCODED_TYPE), ParameterizedHeaderWithValue.fromString("gzip, pkunzip")), equalTo(new Variant(MediaType.APPLICATION_JSON_TYPE, "es", "gzip")));
        assertThat(MuVariantListBuilder.selectVariant(availableVariants, acceptLang("en-US,en-NZ;q=0.5, zh-CN"), asList(MediaType.APPLICATION_JSON_TYPE, MediaType.APPLICATION_FORM_URLENCODED_TYPE), ParameterizedHeaderWithValue.fromString("gzip, pkunzip")), equalTo(new Variant(MediaType.APPLICATION_JSON_TYPE, "en", "NZ", "gzip")));
        assertThat(MuVariantListBuilder.selectVariant(availableVariants, acceptLang("en-NZ, zh-CN"), asList(MediaType.APPLICATION_JSON_TYPE, MediaType.APPLICATION_FORM_URLENCODED_TYPE), ParameterizedHeaderWithValue.fromString("gzip, pkunzip")), equalTo(new Variant(MediaType.APPLICATION_JSON_TYPE, "en", "NZ", "gzip")));
        assertThat(MuVariantListBuilder.selectVariant(availableVariants, acceptLang("en, zh-CN"), asList(MediaType.APPLICATION_JSON_TYPE, MediaType.APPLICATION_FORM_URLENCODED_TYPE), ParameterizedHeaderWithValue.fromString("pkunzip")), nullValue());
        assertThat(MuVariantListBuilder.selectVariant(availableVariants, acceptLang("en, zh-CN"), singletonList(MediaType.APPLICATION_FORM_URLENCODED_TYPE), ParameterizedHeaderWithValue.fromString("gzip, pkunzip")), nullValue());
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
        assertThat(MuVariantListBuilder.selectVariant(availableVariants, acceptLang("en, zh-CN"), singletonList(MediaType.APPLICATION_FORM_URLENCODED_TYPE), ParameterizedHeaderWithValue.fromString("gzip, pkunzip")), nullValue());
        assertThat(MuVariantListBuilder.selectVariant(availableVariants, acceptLang("zh-CN"), asList(MediaType.APPLICATION_JSON_TYPE, MediaType.APPLICATION_FORM_URLENCODED_TYPE), ParameterizedHeaderWithValue.fromString("gzip, pkunzip")), nullValue());
    }

    @Test
    public void itCanSelectTheBestVariantIgnoringLanguage() {
        List<Variant> availableVariants = Variant.VariantListBuilder.newInstance()
            .mediaTypes(MediaType.APPLICATION_XML_TYPE, MediaType.APPLICATION_JSON_TYPE)
            .encodings("deflate", "gzip").build();
        assertThat(MuVariantListBuilder.selectVariant(availableVariants, acceptLang("en, zh-CN"), asList(MediaType.APPLICATION_JSON_TYPE, MediaType.APPLICATION_FORM_URLENCODED_TYPE), ParameterizedHeaderWithValue.fromString("gzip, pkunzip")), equalTo(new Variant(MediaType.APPLICATION_JSON_TYPE, (Locale)null, "gzip")));
        assertThat(MuVariantListBuilder.selectVariant(availableVariants, acceptLang("en, zh-CN"), asList(MediaType.APPLICATION_JSON_TYPE, MediaType.APPLICATION_FORM_URLENCODED_TYPE), ParameterizedHeaderWithValue.fromString("pkunzip")), nullValue());
        assertThat(MuVariantListBuilder.selectVariant(availableVariants, acceptLang("en, zh-CN"), singletonList(MediaType.APPLICATION_FORM_URLENCODED_TYPE), ParameterizedHeaderWithValue.fromString("gzip, pkunzip")), nullValue());
        assertThat(MuVariantListBuilder.selectVariant(availableVariants, acceptLang("*"), asList(MediaType.APPLICATION_JSON_TYPE, MediaType.APPLICATION_FORM_URLENCODED_TYPE), ParameterizedHeaderWithValue.fromString("gzip, pkunzip")), equalTo(new Variant(MediaType.APPLICATION_JSON_TYPE, (Locale)null, "gzip")));
    }

    @Test
    public void languageSelectionDependsOnQAndSpecificity() {
        Variant en = new Variant(null, "en", null);
        Variant enUS = new Variant(null, "en", "US", null);
        Variant zhCN = new Variant(null, "zh", "CN", null);
        List<Variant> availableVariants = asList(en, enUS, zhCN);
        assertThat(MuVariantListBuilder.selectVariant(availableVariants, acceptLang("en"), emptyList(), emptyList()), equalTo(en));
        assertThat(MuVariantListBuilder.selectVariant(availableVariants, acceptLang("en-US"), emptyList(), emptyList()), equalTo(enUS));
        assertThat(MuVariantListBuilder.selectVariant(availableVariants, acceptLang("en-US;q=0.9, en"), emptyList(), emptyList()), equalTo(en));
        assertThat(MuVariantListBuilder.selectVariant(availableVariants, acceptLang("en-NZ,en;q=0.5, zh-CN"), emptyList(), emptyList()), equalTo(en));
        assertThat(MuVariantListBuilder.selectVariant(availableVariants, acceptLang("en-NZ"), emptyList(), emptyList()), equalTo(en));
        assertThat(MuVariantListBuilder.selectVariant(availableVariants, acceptLang("en-NZ-auck"), emptyList(), emptyList()), equalTo(en));
        assertThat(MuVariantListBuilder.selectVariant(availableVariants, acceptLang("zh"), emptyList(), emptyList()), nullValue());
        assertThat(MuVariantListBuilder.selectVariant(availableVariants, acceptLang("zh-CN"), emptyList(), emptyList()), equalTo(zhCN));
        assertThat(MuVariantListBuilder.selectVariant(availableVariants, acceptLang("zh-TW"), emptyList(), emptyList()), nullValue());
        assertThat(MuVariantListBuilder.selectVariant(availableVariants, acceptLang("fr"), emptyList(), emptyList()), nullValue());
        assertThat(MuVariantListBuilder.selectVariant(availableVariants, acceptLang("*"), emptyList(), emptyList()), oneOf(en, enUS, zhCN));
        assertThat(MuVariantListBuilder.selectVariant(availableVariants, acceptLang("fr, *"), emptyList(), emptyList()), oneOf(en, enUS, zhCN));
    }

    @Test
    public void mediaTypeSelectionDependsOnQAndSpecificity() {
        Variant text = new Variant(new MediaType("text", null), (Locale)null, null);
        Variant textHtml = new Variant(MediaType.TEXT_HTML_TYPE, (Locale)null, null);
        Variant textHtmlUTF8 = new Variant(new MediaType("text", "html", "UTF-8"), (Locale)null, null);
        List<Variant> availableVariants = asList(text, textHtml, textHtmlUTF8);
        assertThat(MuVariantListBuilder.selectVariant(availableVariants, acceptLang("*"), singletonList(new MediaType("application", "xml")), emptyList()), nullValue());
        assertThat(MuVariantListBuilder.selectVariant(availableVariants, acceptLang("*"), singletonList(new MediaType("text", "xml")), emptyList()), equalTo(text));
        assertThat(MuVariantListBuilder.selectVariant(availableVariants, acceptLang("*"), singletonList(new MediaType("text", "markdown")), emptyList()), equalTo(text));
        assertThat(MuVariantListBuilder.selectVariant(availableVariants, acceptLang("*"), singletonList(new MediaType("text", null)), emptyList()), equalTo(textHtmlUTF8));
        assertThat(MuVariantListBuilder.selectVariant(availableVariants, acceptLang("*"), singletonList(new MediaType("text", "html")), emptyList()), equalTo(textHtmlUTF8));
        assertThat(MuVariantListBuilder.selectVariant(availableVariants, acceptLang("*"), asList(new MediaType("text", "html", "UTF-8"), new MediaType("text", "html")), emptyList()), equalTo(textHtmlUTF8));
        assertThat(MuVariantListBuilder.selectVariant(availableVariants, acceptLang("*"), asList(new MediaType("text", "html", "ascii"), new MediaType("text", "html", "custom")), emptyList()), nullValue());
        assertThat(MuVariantListBuilder.selectVariant(availableVariants, acceptLang("*"), asList(new MediaType("text", "html", "ascii"), new MediaType("text", "html", "custom"), new MediaType("text", "html", "utf-8")), emptyList()), equalTo(textHtmlUTF8));
    }

    @Test
    public void ifTheServerIsSpecificButTheClientIsNotThenSelectTheSpecificOne() {
        Variant textHtmlUTF8 = new Variant(new MediaType("text", "html", "UTF-8"), (Locale)null, null);
        List<Variant> availableVariants = singletonList(textHtmlUTF8);
        assertThat(MuVariantListBuilder.selectVariant(availableVariants, acceptLang("*"), singletonList(new MediaType(null, null)), emptyList()), equalTo(textHtmlUTF8));
        assertThat(MuVariantListBuilder.selectVariant(availableVariants, acceptLang("*"), singletonList(new MediaType("text", null)), emptyList()), equalTo(textHtmlUTF8));
        assertThat(MuVariantListBuilder.selectVariant(availableVariants, acceptLang("*"), singletonList(new MediaType("text", "html")), emptyList()), equalTo(textHtmlUTF8));
        assertThat(MuVariantListBuilder.selectVariant(availableVariants, acceptLang("*"), asList(new MediaType("text", "html", "UTF-8"), new MediaType("text", "html")), emptyList()), equalTo(textHtmlUTF8));
        assertThat(MuVariantListBuilder.selectVariant(availableVariants, acceptLang("*"), asList(new MediaType("text", "html", "ascii"), new MediaType("text", "html", "custom")), emptyList()), nullValue());
        assertThat(MuVariantListBuilder.selectVariant(availableVariants, acceptLang("*"), asList(new MediaType("text", "html", "ascii"), new MediaType("text", "html", "custom"), new MediaType("text", "html", "utf-8")), emptyList()), equalTo(textHtmlUTF8));
    }

    @Test
    public void encodingSelectionDependsOnQ() {
        Variant gzip = new Variant(null, (Locale)null, "gzip");
        Variant pkunzip = new Variant(null, (Locale)null, "pkunzip");
        List<Variant> availableVariants = asList(gzip, pkunzip);
        assertThat(MuVariantListBuilder.selectVariant(availableVariants, acceptLang("*"), emptyList(), ParameterizedHeaderWithValue.fromString("gzip")), equalTo(gzip));
        assertThat(MuVariantListBuilder.selectVariant(availableVariants, acceptLang("*"), emptyList(), ParameterizedHeaderWithValue.fromString("pkunzip, compress")), equalTo(pkunzip));
        assertThat(MuVariantListBuilder.selectVariant(availableVariants, acceptLang("*"), emptyList(), ParameterizedHeaderWithValue.fromString("pkunzip;q=0.5, compress, gzip")), equalTo(gzip));
        assertThat(MuVariantListBuilder.selectVariant(availableVariants, acceptLang("*"), emptyList(), ParameterizedHeaderWithValue.fromString("pkunzip;q=0.8,gzip;q=0.5")), equalTo(pkunzip));
        assertThat(MuVariantListBuilder.selectVariant(availableVariants, acceptLang("*"), emptyList(), ParameterizedHeaderWithValue.fromString("compress")), nullValue());
    }

    List<Locale.LanguageRange> acceptLang(String s) {
        return Locale.LanguageRange.parse(s);
    }


}