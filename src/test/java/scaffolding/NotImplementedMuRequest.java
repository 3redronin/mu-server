package scaffolding;

import io.muserver.*;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class NotImplementedMuRequest implements MuRequest {
    
    private <T> T throwIt() {
        throw new RuntimeException("Not implemented");
    }
    
    @Override
    public String contentType() {
        return throwIt();
    }

    @Override
    public long startTime() {
        return throwIt();
    }

    @Override
    public Method method() {
        return throwIt();
    }

    @Override
    public URI uri() {
        return throwIt();
    }

    @Override
    public URI serverURI() {
        return throwIt();
    }

    @Override
    public Headers headers() {
        return throwIt();
    }

    @Override
    public Optional<InputStream> inputStream() {
        return Optional.empty();
    }

    @Override
    public String readBodyAsString() throws IOException {
        return throwIt();
    }

    @Override
    public List<UploadedFile> uploadedFiles(String name) throws IOException {
        return throwIt();
    }

    @Override
    public UploadedFile uploadedFile(String name) throws IOException {
        return throwIt();
    }

    @Override
    public RequestParameters query() {
        return throwIt();
    }

    @Override
    public RequestParameters form() throws IOException {
        return throwIt();
    }

    @Override
    @Deprecated
    public String parameter(String name) {
        return throwIt();
    }

    @Override
    @Deprecated
    public List<String> parameters(String name) {
        return throwIt();
    }

    @Override
    @Deprecated
    public String formValue(String name) throws IOException {
        return throwIt();
    }

    @Override
    @Deprecated
    public List<String> formValues(String name) throws IOException {
        return throwIt();
    }

    @Override
    public List<Cookie> cookies() {
        return throwIt();
    }

    @Override
    public Optional<String> cookie(String name) {
        return Optional.empty();
    }

    @Override
    public String contextPath() {
        return throwIt();
    }

    @Override
    public String relativePath() {
        return throwIt();
    }

    @Override
    @Deprecated
    public Object state() {
        return throwIt();
    }

    @Override
    @Deprecated
    public void state(Object value) {
        throwIt();
    }

    @Override
    public Object attribute(String key) {
        return throwIt();
    }

    @Override
    public void attribute(String key, Object value) {
        throwIt();
    }

    @Override
    public Map<String, Object> attributes() {
        return throwIt();
    }

    @Override
    public AsyncHandle handleAsync() {
        return throwIt();
    }

    @Override
    public String remoteAddress() {
        return throwIt();
    }

    @Override
    public String clientIP() {
        return throwIt();
    }

    @Override
    public MuServer server() {
        return throwIt();
    }

    @Override
    public boolean isAsync() {
        return throwIt();
    }

    @Override
    @Deprecated
    public String protocol() {
        return throwIt();
    }

    @Override
    public HttpConnection connection() {
        return throwIt();
    }
}
