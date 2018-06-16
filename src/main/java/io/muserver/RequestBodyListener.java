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
 *              public void onDataReceived(ByteBuffer bb) {
 *                  byte[] b = new byte[bb.remaining()];
 *                  bb.get(b);
 *                  try {
 *                      response.outputStream().write(b);
 *                  } catch (IOException e) {
 *                      errors.add(e);
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
     * @param buffer A buffer holding some of the request body data
     */
    void onDataReceived(ByteBuffer buffer);

    /**
     * Called when the request body is fully received.
     */
    void onComplete();

    /**
     * Called if there is an error reading the body.
     * @param t The error.
     */
    void onError(Throwable t);

}
