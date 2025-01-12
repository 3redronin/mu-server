package io.muserver;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Set;

/**
 * An encoder and decoder for response body content.
 *
 * <p>For example a GZIP encoder is used to compress text-based response bodies.</p>
 */
@NullMarked
public interface ContentEncoder {

    /**
     * The content coding of this encoder.
     *
     * <p>See the <a href="https://www.iana.org/assignments/http-parameters/http-parameters.xhtml#content-coding">HTTP
     * Content Coding Registry</a> for a list of registered codings.</p>
     * @return the content coding for this encoder.
     */
    String contentCoding();

    /**
     * Checks to see if this encoder can encode the response stream, and if so prepare the
     * response headers, and return <code>true</code>.
     * <p>If <code>true</code> is returned then mu-server will call {@link #wrapStream(MuRequest, MuResponse, OutputStream)}
     * on this encoder. If this encoder cannot support this request and response then it returns <code>false</code>.</p>
     * <p>Encoders are checked in the order they are registered, and the first one to return <code>true</code>
     * will be used for the encoder. If all return <code>false</code> then the response stream will not be
     * encoded.</p>
     * @see MuServerBuilder#withContentEncoders(List) setting custom encoders
     * @param request The client request.
     * @param response The response, whose headers may be changed if this encoder will encode the stream.
     * @return <code>true</code> if this encoder will encode this stream; otherwise <code>false</code>.
     */
    boolean prepare(MuRequest request, MuResponse response);

    /**
     * Wraps the given stream in an output stream that encodes the response.
     *
     * <p>This will only be called if {@link #prepare(MuRequest, MuResponse)} has return <code>true</code>
     * for the same request and response.</p>
     *
     * <p>Example implementation:</p>
     * <pre><code>return new GZIPOutputStream(stream, true);</code></pre>
     *
     * @param request The request
     * @param response The response to potentially encode
     * @param stream The response stream, which should be wrapped by an encoding stream if supported.
     * @return An encoding output stream if this encoder supports it, otherwise <code>null</code>
     */
    @Nullable
    OutputStream wrapStream(MuRequest request, MuResponse response, OutputStream stream) throws IOException;

    /**
     * A default prepare implementation that can be called from {@link #prepare(MuRequest, MuResponse)} which performs
     * the following:
     *
     * <ol>
     *     <li>returns <code>false</code> if there is no response content type</li>
     *     <li>returns <code>false</code> if there is a content type but it is not in the given mime type list</li>
     *     <li>adds <code>accept-encoding</code> to the <code>vary</code> header</li>
     *     <li>returns <code>false</code> if there is already a content encoding response header (so that bodies are not encoded multiple times)</li>
     *     <li>returns <code>false</code> if the request's <code>accept-encoding</code> header does not include the given <code>contentCoding</code> value</li>
     *     <li>returns <code>false</code> if there is a <code>content-length</code> response header and it is below the given <code>minimumContentLength</code></li>
     *     <li>sets <code>content-encoding</code> to <code>contentCoding</code>, removes any <code>content-length</code> response header, and returns <code>true</code></li>
     * </ol>
     * @param request the request
     * @param response the response
     * @param mimeTypesToEncode the mimetypes, such as <code>text/plain</code>, that should be encoded
     * @param minimumContentLength the minimum size, where known, before this coding should be used
     * @param contentCoding the name of the content coding, such as <code>gzip</code>
     * @return <code>true</code> if the response has been prepared for this encoder
     */
    static boolean defaultPrepare(MuRequest request, MuResponse response, Set<String> mimeTypesToEncode, long minimumContentLength, String contentCoding) {
        var headers = response.headers();

        var responseType = headers.contentType();
        if (responseType == null) return false;

        var mime = responseType.getType() + "/" + responseType.getSubtype();
        boolean mimeTypeOk = mimeTypesToEncode.contains(mime);
        if (!mimeTypeOk) return false;

        var varyHeader = headers.vary();
        varyHeader.addIfMissing(HeaderNames.ACCEPT_ENCODING.toString(), true);
        headers.set(HeaderNames.VARY, varyHeader);

        if (headers.contains(HeaderNames.CONTENT_ENCODING)) return false; // don't re-encode something

        boolean clientSupports = false;
        for (ParameterizedHeaderWithValue acceptEncoding : request.headers().acceptEncoding()) {
            if (acceptEncoding.value().equalsIgnoreCase(contentCoding)) {
                clientSupports = true;
                break;
            }
        }
        if (!clientSupports) return false;
        long contentSize = headers.getLong(HeaderNames.CONTENT_LENGTH.toString(), Long.MAX_VALUE);
        if (contentSize < minimumContentLength) return false;

        headers.set(HeaderNames.CONTENT_ENCODING, contentCoding);
        headers.remove(HeaderNames.CONTENT_LENGTH);

        return true;

    }

}

