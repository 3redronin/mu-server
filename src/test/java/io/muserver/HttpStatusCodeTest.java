package io.muserver;


import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static io.muserver.HttpStatusCode.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;


public class HttpStatusCodeTest {

    @Test
    public void canCreate() {
        assertThat(of(200), sameInstance(HttpStatusCode.OK_200));
        assertThat(of(200, "OK"), sameInstance(HttpStatusCode.OK_200));
        assertThat(of(200, "Good good"), not(equalTo(HttpStatusCode.OK_200)));
        assertThat(of(200, "Good good").sameCode(HttpStatusCode.OK_200), equalTo(true));
        assertThat(HttpStatusCode.CREATED_201.toString(), equalTo("201 Created"));
    }

    @Test
    public void reasonPhraseIsGood() {
        assertThat(NON_AUTHORITATIVE_INFORMATION_203.reasonPhrase(), equalTo("Non-Authoritative Information"));
        assertThat(NON_AUTHORITATIVE_INFORMATION_203.toString(), equalTo("203 Non-Authoritative Information"));
    }

    @Test
    public void canCreateNewOnes() {
        assertThat(HttpStatusCode.of(767).toString(), equalTo("767 Unspecified"));
        assertThat(HttpStatusCode.of(767, "Entangled").toString(), equalTo("767 Entangled"));
    }

    @Test
    public void canCreateResponseLine() {
        assertThat(new String(URI_TOO_LONG_414.http11ResponseLine(), StandardCharsets.US_ASCII),
            equalTo("HTTP/1.1 414 URI Too Long\r\n"));
    }

    @Test
    public void familiesCanBeTested() {
        assertThat(EARLY_HINTS_103.isInformational(), equalTo(true));
        assertThat(MULTI_STATUS_207.isSuccessful(), equalTo(true));
        assertThat(PERMANENT_REDIRECT_308.isRedirection(), equalTo(true));
        assertThat(PAYMENT_REQUIRED_402.isClientError(), equalTo(true));
        assertThat(INSUFFICIENT_STORAGE_507.isServerError(), equalTo(true));

        assertThat(INSUFFICIENT_STORAGE_507.isInformational(), equalTo(false));
        assertThat(EARLY_HINTS_103.isSuccessful(), equalTo(false));
        assertThat(MULTI_STATUS_207.isRedirection(), equalTo(false));
        assertThat(PERMANENT_REDIRECT_308.isClientError(), equalTo(false));
        assertThat(PAYMENT_REQUIRED_402.isServerError(), equalTo(false));
    }

    @Test
    public void mustBeThreeDigitCodes() {
        assertThrows(IllegalArgumentException.class, () -> HttpStatusCode.of(99));
        assertThrows(IllegalArgumentException.class, () -> HttpStatusCode.of(1000));
    }

}