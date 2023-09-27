package io.muserver;


import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class QueryStringTest {

    @Test
    public void emptyReturnsTheEmptyObject() {
        assertThat(qs((String)null), sameInstance(QueryString.EMPTY));
        assertThat(qs(""), sameInstance(QueryString.EMPTY));
    }

    private static QueryString qs(String input) {
        return QueryString.parse(input);
    }

    @Test
    public void basicStuff() {
        assertThat(qs("name=John"), equalTo(qs("name", "John")));
        assertThat(qs("name=John&age=30&city=New%20York"), equalTo(qs(Map.of("name", "John", "age", "30", "city", "New York"))));
    }

    @Test
    public void urlEncodedKeyValuePairsAreOkay() {
        QueryString qs = qs("%21%40%23%24=key%20%3D%20value&%2B%26%3D=%25%26%3D");
        assertThat(qs, equalTo(qs(Map.of("!@#$", "key = value", "+&=", "%&="))));
    }

    @Test
    public void valuesCanBeEmptyOrNull() {
        QueryString qs = qs("&&a&b=&c=hi&&=&&");
        assertThat(qs, equalTo(qs(Map.of("a", "", "b", "", "c", "hi"))));
        assertThat(qs.contains("a"), equalTo(true));
        assertThat(qs.contains("b"), equalTo(true));
        assertThat(qs.contains("c"), equalTo(true));
        assertThat(qs.contains("d"), equalTo(false));
        assertThat(qs.get("a"), equalTo(""));
        assertThat(qs.get("b"), equalTo(""));
        assertThat(qs.get("c"), equalTo("hi"));
        assertThat(qs.get("d"), nullValue());
    }

    @Test
    public void multiValues() {
        assertThat(qs("hobbies=Skiing&hobbies=Coding&hobbies=Reading"), equalTo(qs2(Map.of("hobbies", List.of("Skiing", "Coding", "Reading")))));
    }

    @Test
    public void specialCharactersAreOkay() {
        assertThat(qs("description=Hello%2C%20World!&note=This%20is%20a%20%23note%20with%20%26%20special%20%24%20characters."),
            equalTo(qs(Map.of("description", "Hello, World!", "note", "This is a #note with & special $ characters."))));
    }

    @Test
    public void itIsCaseSensitive() {
        assertThat(qs("hobbies=Skiing&Hobbies=Coding&hobbies=Reading&Hobbies=hobstoves"),
            equalTo(qs2(Map.of("hobbies", List.of("Skiing", "Reading"), "Hobbies", List.of("Coding", "hobstoves")))));
    }

    @Test
    public void encodingsInNames() {
        assertThat(qs("name%5B%5D=John&name%5B%5D=Doe"), equalTo(qs2(Map.of("name[]", List.of("John", "Doe")))));
        assertThat(qs("%24key=%24value&%23key=%23value"), equalTo(qs(Map.of("$key", "$value", "#key", "#value"))));
    }

    @Test
    public void spacesAreOkay() {
        assertThat(qs("na%20me=John%20Doe&loc%20ation=New+York+City"), equalTo(qs(Map.of("na me", "John Doe", "loc ation", "New York City"))));
    }

    @Test
    public void french() {
        assertThat(qs("prénom=Jean&âge=25&ville=Paris"), equalTo(qs(Map.of("prénom", "Jean", "âge", "25", "ville", "Paris"))));
        assertThat(qs("pr%C3%A9nom=Jean&âge=25&ville=Paris"), equalTo(qs(Map.of("prénom", "Jean", "âge", "25", "ville", "Paris"))));
    }

    @Test
    public void japanese() {
        assertThat(qs("名前=田中&年齢=30&都市=東京"), equalTo(qs(Map.of("名前", "田中", "年齢", "30", "都市", "東京"))));
        assertThat(qs("%E5%90%8D%E5%89%8D=%E7%94%B0%E4%B8%AD&%E5%B9%B4%E9%BD%A2=30&%E9%83%BD%E5%B8%82=%E6%9D%B1%E4%BA%AC"), equalTo(qs(Map.of("名前", "田中", "年齢", "30", "都市", "東京"))));
    }

    @Test
    public void arabic() {
        assertThat(qs("الاسم=علي&العمر=22&المدينة=القاهرة"), equalTo(qs(Map.of("الاسم", "علي", "العمر", "22", "المدينة", "القاهرة"))));
        assertThat(qs("%D8%A7%D9%84%D8%A7%D8%B3%D9%85=%D8%B9%D9%84%D9%8A&%D8%A7%D9%84%D8%B9%D9%85%D8%B1=22&%D8%A7%D9%84%D9%85%D8%AF%D9%8A%D9%86%D8%A9=%D8%A7%D9%84%D9%82%D8%A7%D9%87%D8%B1%D8%A9"), equalTo(qs(Map.of("الاسم", "علي", "العمر", "22", "المدينة", "القاهرة"))));
    }

    private QueryString qs(String name, String value) {
        return qs2(Map.of(name, List.of(value)));
    }
    private QueryString qs(Map<String, String> map) {
        var created = new LinkedHashMap<String, List<String>>();
        for (String s : map.keySet()) {
            created.put(s, List.of(map.get(s)));
        }
        return new QueryString(created);
    }
    private QueryString qs2(Map<String, List<String>> map) {
        return new QueryString(new LinkedHashMap<>(map));
    }

}