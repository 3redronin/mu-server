package io.muserver;

import java.nio.ByteBuffer;

/**
 * <p>Callbacks for reading request body data asynchronously.</p>
 * <p>Example usage:</p>
 * <pre><code>
 *  server = httpsServer()
 *      .addHandler((request, response) -&gt; {
 *          AsyncHandle ctx = request.handleAsync();
 *          ctx.setReadListener(new RequestBodyListener() {
 *              public void onDataReceived(ByteBuffer bb, DoneCallback doneCallback) {
 *                  byte[] b = new byte[bb.remaining()];
 *                  bb.get(b);
 *                  try {
 *                      response.outputStream().write(b);
 *                      doneCallback.onComplete(null);
 *                  } catch (IOException e) {
 *                      doneCallback.onComplete(e);
 *                  }
 *              }
 *
 *              public void onComplete() {
 *                  ctx.complete();
 *              }
 *
 *              public void onError(Throwable t) {
 *                  errors.add(t);
 *              }
 *          });
 *
 *          return true;
 *      })
 *      .start();
 * </code></pre>
 */
public interface RequestBodyListener {

    /**
     * <p>Called when request data is received from the client.</p>
     * <p>NOTE: this method should not block as it runs on a socket acceptor thread. If you need to do any blocking operations
     * it is recommended you process the data on another thread.</p>
     *
     * @param buffer       A buffer holding some of the request body data
     * @param doneCallback This must be called when the buffer is no longer needed
     * @throws Exception Any thrown exceptions will cause the {@link #onError(Throwable)} method to be called with the
     *                   thrown exception as a parameter.
     */
    void onDataReceived(ByteBuffer buffer, DoneCallback doneCallback) throws Exception;

    /**
     * Called when the request body is fully received.
     * <p>Note that if the client has sent any trailers they will be available at {@link MuRequest#trailers()} when
     * this is invoked.</p>
     */
    void onComplete();

    /**
     * Called if there is an error reading the body.
     *
     * @param t The error.
     */
    void onError(Throwable t);

}
