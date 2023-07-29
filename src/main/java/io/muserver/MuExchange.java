package io.muserver;

class MuExchange {

    private HttpExchangeState state = HttpExchangeState.IN_PROGRESS;
    final MuExchangeData data;
    final MuRequestImpl request;
    final MuResponseImpl response;

    MuExchange(MuExchangeData data, MuRequestImpl request, MuResponseImpl response) {
        this.data = data;
        this.request = request;
        this.response = response;
    }
}
class MuExchangeData {
    final MuServer2 server;
    final HttpVersion httpVersion;
    final MuHeaders requestHeaders;

    MuExchangeData(MuServer2 server, HttpVersion httpVersion, MuHeaders requestHeaders) {
        this.server = server;
        this.httpVersion = httpVersion;
        this.requestHeaders = requestHeaders;
    }
}