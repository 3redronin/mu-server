package io.muserver;

class MuExchange {

    HttpExchangeState state = HttpExchangeState.IN_PROGRESS;
    final MuExchangeData data;
    final MuRequestImpl request;
    final MuResponseImpl response;

    MuExchange(MuExchangeData data, MuRequestImpl request, MuResponseImpl response) {
        this.data = data;
        this.request = request;
        this.response = response;
    }

    void onRequestCompleted() {
        if (response.responseState().endState()) onCompleted();
    }

    void onResponseCompleted() {
        if (request.requestState().endState()) onCompleted();
    }

    private void onCompleted() {
        boolean good = response.responseState().completedSuccessfully() && request.requestState() == RequestState.COMPLETE;
        this.state = good ? HttpExchangeState.COMPLETE : HttpExchangeState.ERRORED;
        this.data.server.stats.onRequestEnded(request);
    }
}
class MuExchangeData {
    final MuServer2 server;
    final MuHttp1Connection connection;
    final HttpVersion httpVersion;
    final MuHeaders requestHeaders;

    MuExchangeData(MuServer2 server, MuHttp1Connection connection, HttpVersion httpVersion, MuHeaders requestHeaders) {
        this.server = server;
        this.connection = connection;
        this.httpVersion = httpVersion;
        this.requestHeaders = requestHeaders;
    }
}