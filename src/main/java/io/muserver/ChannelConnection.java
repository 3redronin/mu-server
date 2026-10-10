package io.muserver;

import org.jspecify.annotations.Nullable;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLPeerUnverifiedException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.channels.WritableByteChannel;
import java.security.cert.Certificate;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Channel/TLS owner state; control methods only publish requests or immediately abort the channel. */
final class ChannelConnection implements ConnectionTransport {
    private static final int BUFFER_SIZE = 8192;
    private static final int TURN_LIMIT = 16;
    private static final long SETUP_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(10);
    private static final long CLOSE_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(1);
    private final ChannelTransportLoop loop;
    private final ExecutionResources.TransportLease executionLease;
    final Socket socket;
    private final SocketChannel channel;
    private final ConnectionAcceptedTime acceptedTime;
    private final boolean http2Enabled;
    private final InetSocketAddress remote;
    private final InetSocketAddress local;
    private final boolean secure;
    final AtomicBoolean scheduled = new AtomicBoolean();
    private final AtomicBoolean aborted = new AtomicBoolean();
    private final TransportInputBuffer input = new TransportInputBuffer(BUFFER_SIZE, this::schedule, this::schedule);
    private final TransportOutputBuffer output = new TransportOutputBuffer(BUFFER_SIZE, this::schedule);
    private ByteBuffer incoming = ByteBuffer.allocate(BUFFER_SIZE).flip();
    private final ByteBuffer protocolPrefix = ByteBuffer.allocate(Http2Handshaker.clientConnectionPrefaceLength());
    private final @Nullable ProxyProtocolDecoder proxy;
    private @Nullable ProxiedConnectionInfo proxyInfo;
    private @Nullable SelectionKey key;
    private @Nullable TlsEngineDriver tls;
    private @Nullable BaseHttpConnection connection;
    private Http1Connection.@Nullable ReadDriver http1;
    private Http2Connection.@Nullable ReadDriver http2;
    private @Nullable CompletableFuture<?> protocolCompletion;
    private volatile boolean retired;
    private volatile boolean closeRequested;
    private volatile boolean stopReceiving;
    private volatile @Nullable TlsInfo tlsInfo;
    private boolean initialized;
    private boolean proxyComplete;
    private @Nullable ByteBuffer prefetchedCiphertext;
    private boolean networkEof;
    private boolean networkCloseObserved;
    private boolean inputEnded;
    private boolean tlsCloseRequested;
    private boolean failureNotified;
    private boolean setupFailureRecorded;
    private boolean ownerFailed;
    private @Nullable IOException failure;
    private long setupDeadline = System.nanoTime() + SETUP_TIMEOUT_NANOS;
    private long closeDeadline;
    private long publishedHandshake;

    private final WritableByteChannel tlsSink = new WritableByteChannel() {
        @Override public int write(ByteBuffer source) throws IOException { return java.util.Objects.requireNonNull(tls).writePlaintext(source); }
        @Override public boolean isOpen() { return channel.isOpen(); }
        @Override public void close() { }
    };

    ChannelConnection(ChannelTransportLoop loop, Socket socket, ConnectionAcceptedTime acceptedTime,
                      boolean http2Enabled) throws IOException {
        this.loop = loop;
        this.socket = socket;
        this.channel = java.util.Objects.requireNonNull(socket.getChannel());
        this.acceptedTime = acceptedTime;
        this.http2Enabled = http2Enabled;
        this.remote = (InetSocketAddress) channel.getRemoteAddress();
        this.local = (InetSocketAddress) channel.getLocalAddress();
        this.secure = loop.acceptor.isHttps();
        proxy = loop.proxyConfig == null ? null : new ProxyProtocolDecoder(loop.proxyConfig);
        proxyComplete = proxy == null;
        executionLease = loop.server.retainTransport();
    }

    @Nullable BaseHttpConnection connection() { return connection; }
    boolean isRetired() { return retired; }

    boolean schedule() {
        return !retired && (!scheduled.compareAndSet(false, true) || loop.enqueue(this));
    }

    void drive() {
        if (retired) return;
        try {
            if (!initialized) {
                initialized = true;
                if (aborted.get() || !channel.isOpen()) { closeNetwork(); retireIfDone(); return; }
                channel.configureBlocking(false);
                key = channel.register(loop.selector, 0, this);
                if (proxyComplete) beginProtocolSetup();
            }
            boolean progress = false;
            for (int turn = 0; turn < TURN_LIMIT; turn++) {
                progress = advance();
                if (!progress || retired) break;
            }
            if (!retired) {
                updateInterest();
                if (progress) schedule();
            }
        } catch (Throwable error) {
            fail(error instanceof IOException ? (IOException) error : new IOException("Channel progression failed", error));
            FatalErrors.rethrow(error);
        }
    }

    private boolean advance() throws IOException {
        if (aborted.get() || !channel.isOpen()) {
            networkCloseObserved = true;
            if (tls != null) { tls.abort(new IOException("Channel closed")); tls = null; }
            failBuffers(new IOException("Channel closed"));
            notifyProtocolFailure();
            boolean progressed = http1 != null && http1.advance();
            if (http2 != null) http2.inputAvailable();
            retireIfDone();
            return progressed;
        }
        if (failure != null) {
            boolean progressed = false;
            if (tls != null && !tls.failureCloseDone()) {
                progressed = tls.advanceFailureClose();
                progressed |= tls.drainFailureTo(channel, BUFFER_SIZE) > 0;
            }
            if (tls == null || tls.failureCloseDone() || deadlinePassed(closeDeadline)) closeNetwork();
            return progressed;
        }
        boolean progress = false;
        if (!proxyComplete) {
            progress = readPlainChannel();
            proxyInfo = java.util.Objects.requireNonNull(proxy).decode(incoming);
            if (proxyInfo != null) {
                if (!loop.acceptor.channelPreambleFinished(socket)) { closeNetwork(); return true; }
                proxyComplete = true;
                beginProtocolSetup();
                return true;
            }
            if (networkEof) proxy.endOfInput();
            return progress;
        }
        if (tls != null) {
            long before = tls.revision();
            if (prefetchedCiphertext != null) {
                if (tls.inputCapacity() > 0) tls.receive(prefetchedCiphertext);
                if (!prefetchedCiphertext.hasRemaining()) prefetchedCiphertext = null;
            } else if (!stopReceiving && tls.inputCapacity() > 0) {
                if (tls.readFrom(channel, BUFFER_SIZE) > 0) networkProgress();
            }
            tls.advance();
            if (tls.drainEncryptedTo(channel, BUFFER_SIZE) > 0) networkProgress();
            if (tls.inboundDone() && !tls.handshakeComplete()) throw new IOException("TLS closed before handshake completion");
            if (tls.handshakeComplete() && !tls.tasksPending()) {
                publishTlsInfo();
                if (connection == null) promote("h2".equals(tls.applicationProtocol()) ? HttpVersion.HTTP_2 : HttpVersion.HTTP_1_1);
            }
            if (tls.plaintextSize() > 0 && incoming.remaining() < incoming.capacity()) {
                incoming.compact();
                try { tls.readPlaintext(incoming); }
                finally { incoming.flip(); }
            }
            progress = before != tls.revision();
        } else if (!stopReceiving && !closeRequested) {
            progress = readPlainChannel();
        }
        if (connection == null && tls == null) {
            if (!http2Enabled) promote(HttpVersion.HTTP_1_1);
            else {
                while (incoming.hasRemaining() && protocolPrefix.hasRemaining()) {
                    protocolPrefix.put(incoming.get());
                    progress = true;
                    if (!Http2Handshaker.isClientPrefacePrefix(protocolPrefix.array(), protocolPrefix.position())) {
                        promote(HttpVersion.HTTP_1_1);
                        break;
                    }
                }
                if (connection == null && !protocolPrefix.hasRemaining()) promote(HttpVersion.HTTP_2);
                if (connection == null && networkEof) promote(HttpVersion.HTTP_1_1);
            }
        }
        BaseHttpConnection current = connection;
        if (current != null) {
            if (incoming.hasRemaining() && input.remainingCapacity() > 0) {
                int count = input.offer(incoming);
                if (count > 0) { current.onBytesRead(count); progress = true; }
            }
            boolean peerEnded = tls == null ? networkEof : tls.inboundDone();
            if (!inputEnded && peerEnded && !incoming.hasRemaining()) {
                inputEnded = true;
                input.endOfInput();
                progress = true;
            }
            if (output.pendingBytes() > 0) {
                long beforeWrite = tls == null ? 0 : tls.revision();
                int written = output.drainTo(tls == null ? channel : tlsSink, BUFFER_SIZE);
                if (written > 0) { networkProgress(); progress = true; }
                // Wrapping can produce output without acknowledging plaintext yet.
                if (tls != null && tls.revision() != beforeWrite) progress = true;
            }
            if (http1 != null) progress |= http1.advance();
            if (http2 != null) http2.inputAvailable();
            if (protocolCompletion != null && protocolCompletion.isDone()) closeRequested = true;
        }
        if (closeRequested) {
            // Protocol completion can leave an unawaited WebSocket send in the output ring.
            // Bound that drain too: HTTP/1 has already published CLOSED, so its idle-timeout
            // path cannot be relied on to abort a peer that stopped reading after input EOF.
            if (closeDeadline == 0) closeDeadline = System.nanoTime() + CLOSE_TIMEOUT_NANOS;
            if (output.pendingBytes() == 0) {
                if (tls == null) { closeNetwork(); return true; }
                if (!tlsCloseRequested) { tlsCloseRequested = true; tls.closeOutbound(); progress = true; }
                if (tls.outboundDone() || deadlinePassed(closeDeadline)) { closeNetwork(); return true; }
            }
        }
        return progress;
    }

    private void beginProtocolSetup() throws IOException {
        if (proxy != null) setupDeadline = System.nanoTime() + SETUP_TIMEOUT_NANOS;
        HttpsConfig config = loop.acceptor.httpsConfig();
        if (config != null) {
            tls = new TlsEngineDriver(config.createEngine(http2Enabled), loop.tlsTasks, this::schedule);
            if (incoming.hasRemaining()) {
                // Keep coalesced ciphertext independent of plaintext capacity. A provider may
                // produce plaintext before it can admit all the prefetched encrypted bytes.
                prefetchedCiphertext = incoming;
                incoming = ByteBuffer.allocate(BUFFER_SIZE).flip();
            }
        } else if (!http2Enabled) promote(HttpVersion.HTTP_1_1);
    }

    private void promote(HttpVersion version) throws IOException {
        BaseHttpConnection promoted = loop.acceptor.promoteChannel(socket, this, acceptedTime, proxyInfo, version);
        if (promoted == null) { closeNetwork(); return; }
        connection = promoted;
        protocolPrefix.flip();
        if (protocolPrefix.hasRemaining()) promoted.onBytesRead(input.offer(protocolPrefix));
        var clientOut = new HttpConnectionOutputStream(promoted, output);
        if (promoted instanceof Http1Connection) {
            http1 = ((Http1Connection) promoted).readDriver(input, clientOut, this::schedule);
            protocolCompletion = http1.completion();
        } else {
            http2 = ((Http2Connection) promoted).readDriver(input, output.asynchronousWriter());
            protocolCompletion = http2.completion();
            http2.inputAvailable();
        }
        protocolCompletion.whenComplete((ignored, error) -> schedule());
    }

    private boolean readPlainChannel() throws IOException {
        if (networkEof || incoming.remaining() == incoming.capacity()) return false;
        incoming.compact();
        int count;
        try { count = channel.read(incoming); }
        finally { incoming.flip(); }
        if (count == -1) { networkEof = true; return true; }
        if (count > 0) networkProgress();
        return count > 0;
    }

    private void publishTlsInfo() {
        TlsEngineDriver driver = java.util.Objects.requireNonNull(tls);
        if (publishedHandshake == driver.handshakeGeneration()) return;
        publishedHandshake = driver.handshakeGeneration();
        var session = driver.session();
        Certificate certificate = tlsInfo == null ? null : tlsInfo.certificate;
        if (tlsInfo == null) {
            try {
                Certificate[] certificates = session.getPeerCertificates();
                if (certificates.length > 0) certificate = certificates[0];
            } catch (SSLPeerUnverifiedException ignored) { }
        }
        tlsInfo = new TlsInfo(session.getProtocol(), session.getCipherSuite(),
            SocketConnectionTransport.requestedServerName(session), certificate);
    }

    private void networkProgress() {
        BaseHttpConnection current = connection;
        if (current != null) current.onNetworkProgress();
    }

    private void updateInterest() {
        SelectionKey current = key;
        if (current == null || !current.isValid()) return;
        boolean read = failure == null && !stopReceiving && (tls == null
            ? !networkEof && incoming.remaining() < incoming.capacity()
            : prefetchedCiphertext == null && tls.inputCapacity() > 0);
        boolean write = tls == null ? output.pendingBytes() > 0 : tls.encryptedOutputSize() > 0;
        current.interestOps((read ? SelectionKey.OP_READ : 0) | (write ? SelectionKey.OP_WRITE : 0));
    }

    void checkDeadline(long now, boolean stopping) {
        boolean setupExpired = connection == null && proxyComplete && now - setupDeadline >= 0;
        boolean closeExpired = channel.isOpen() && closeDeadline != 0 && now - closeDeadline >= 0;
        if (!stopping && setupExpired && channel.isOpen()) recordSetupFailure();
        if (channel.isOpen() && (stopping || setupExpired || closeExpired)) {
            try { abort(); } catch (IOException ignored) { }
        } else if (!channel.isOpen() && !networkCloseObserved) schedule();
    }

    private void recordSetupFailure() {
        if (connection == null && !setupFailureRecorded) {
            setupFailureRecorded = true;
            loop.acceptor.channelSetupFailed(socket);
        }
    }

    private static boolean deadlinePassed(long deadline) { return deadline != 0 && System.nanoTime() - deadline >= 0; }

    private void fail(IOException error) {
        if (failure == null) {
            failure = error;
            // TLS task rejection is wrapped by the engine driver as a handshake failure.
            Throwable cause = error;
            for (int depth = 0; cause != null && depth < 32; depth++, cause = cause.getCause()) {
                if (cause instanceof RejectedExecutionException) {
                    loop.server.getStatsImpl().onRejectedDueToOverload();
                    break;
                }
            }
        }
        failBuffers(error);
        recordSetupFailure();
        if (closeDeadline == 0) closeDeadline = System.nanoTime() + CLOSE_TIMEOUT_NANOS;
        if (tls == null || !(error instanceof SSLException)) closeNetwork();
        schedule();
    }

    private void failBuffers(IOException error) { input.fail(error); output.fail(error); }

    private void closeNetwork() {
        try { channel.close(); } catch (IOException ignored) { }
        SelectionKey current = key;
        if (current != null) current.cancel();
        schedule();
    }

    private void notifyProtocolFailure() {
        BaseHttpConnection current = connection;
        // H2's reader handles a failed bridge and deliberately retains fully delivered
        // responses until application completion/idle timeout. Do not turn that into an abort.
        // H1 needs explicit cancellation when an async exchange has no input operation pending.
        if (!(current instanceof Http1Connection) || closeRequested || failureNotified
            || (protocolCompletion != null && protocolCompletion.isDone())) return;
        failureNotified = true;
        try {
            loop.server.executeInternalTask(() -> {
                try { current.abort(); } catch (IOException ignored) { }
            });
        } catch (RejectedExecutionException ignored) {
            // During executor shutdown, the acceptor already owns protocol cancellation.
        }
    }

    private void retireIfDone() {
        if (retired || channel.isOpen() || (protocolCompletion != null && !protocolCompletion.isDone())) return;
        retire();
    }

    private void retire() {
        retired = true;
        tls = null;
        prefetchedCiphertext = null;
        http1 = null;
        http2 = null;
        protocolCompletion = null;
        incoming = ByteBuffer.allocate(0);
        SelectionKey current = key;
        if (current != null) current.attach(null);
        key = null;
        try { loop.retired(this); }
        finally { executionLease.close(); }
    }

    void loopFailed() {
        if (retired || ownerFailed) return;
        ownerFailed = true;
        try { abort(); } catch (IOException ignored) { }
        notifyProtocolFailure();
        // Completion must continue even though the readiness owner is gone. Retain an active
        // handler's accounting until its work actually returns, just as during forced shutdown.
        if (http1 != null) http1.ownerFailed(new IOException("Channel readiness owner stopped"));
        if (http2 != null) http2.inputAvailable();
        CompletableFuture<?> completion = protocolCompletion;
        if (completion == null) retire();
        else completion.whenComplete((ignored, error) -> retire());
    }

    @Override public InetSocketAddress remoteAddress() { return remote; }
    @Override public InetSocketAddress localAddress() { return local; }
    @Override public boolean isSecure() { return secure; }
    @Override public @Nullable String tlsProtocol() { TlsInfo info = tlsInfo; return info == null ? null : info.protocol; }
    @Override public @Nullable String cipherSuite() { TlsInfo info = tlsInfo; return info == null ? null : info.cipher; }
    @Override public @Nullable String sniHostName() { TlsInfo info = tlsInfo; return info == null ? null : info.sni; }
    @Override public @Nullable Certificate clientCertificate() { TlsInfo info = tlsInfo; return info == null ? null : info.certificate; }
    @Override public void readTimeoutMillis(int timeoutMillis) { input.readTimeoutMillis(timeoutMillis); }
    @Override public void shutdownInput() { stopReceiving = true; input.endOfInput(); schedule(); }
    @Override public void close() { closeRequested = true; schedule(); }

    @Override public void abort() throws IOException {
        aborted.set(true);
        failBuffers(new IOException("Channel aborted"));
        try { channel.close(); }
        finally { schedule(); }
    }

    private static final class TlsInfo {
        final String protocol;
        final String cipher;
        final @Nullable String sni;
        final @Nullable Certificate certificate;
        TlsInfo(String protocol, String cipher, @Nullable String sni, @Nullable Certificate certificate) {
            this.protocol = protocol; this.cipher = cipher; this.sni = sni; this.certificate = certificate;
        }
    }
}
