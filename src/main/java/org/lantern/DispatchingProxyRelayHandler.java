package org.lantern;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.HashMap;
import java.util.concurrent.Executors;

import javax.net.ssl.SSLEngine;

import org.apache.commons.lang.StringUtils;
import org.jboss.netty.bootstrap.ClientBootstrap;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.channel.group.DefaultChannelGroup;
import org.jboss.netty.channel.socket.ClientSocketChannelFactory;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpChunk;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpHeaders.Names;
import org.jboss.netty.handler.codec.http.HttpMethod;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpRequestEncoder;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import org.jboss.netty.handler.codec.http.HttpVersion;
import org.jboss.netty.handler.ssl.SslHandler;
import org.lantern.httpseverywhere.HttpsEverywhere;
import org.littleshoot.commom.xmpp.XmppP2PClient;
import org.littleshoot.proxy.DefaultRelayPipelineFactoryFactory;
import org.littleshoot.proxy.HttpConnectRelayingHandler;
import org.littleshoot.proxy.HttpFilter;
import org.littleshoot.proxy.HttpRequestHandler;
import org.littleshoot.proxy.KeyStoreManager;
import org.littleshoot.proxy.ProxyUtils;
import org.littleshoot.proxy.RelayPipelineFactoryFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handler that relays traffic to another proxy, dispatching between 
 * appropriate proxies depending on the type of request.
 */
public class DispatchingProxyRelayHandler extends SimpleChannelUpstreamHandler {

    private final Logger log = LoggerFactory.getLogger(getClass());
    
    private volatile long messagesReceived = 0L;

    /**
     * Outgoing channel that handles incoming HTTP Connect requests.
     */
    private ChannelFuture httpConnectChannelFuture;
    
    private Channel browserToProxyChannel;

    private final ProxyStatusListener proxyStatusListener;
    
    private final ProxyProvider proxyProvider;

    private static final long REQUEST_SIZE_LIMIT = 1024 * 1024 * 10 - 4096;

    private static final boolean PROXIES_ACTIVE = true;
    private static final boolean ANONYMOUS_ACTIVE = true;
    private static final boolean TRUSTED_ACTIVE = true;
    private static final boolean LAE_ACTIVE = true;
    
    private static final ClientSocketChannelFactory clientSocketChannelFactory =
        new NioClientSocketChannelFactory(
            Executors.newCachedThreadPool(),
            Executors.newCachedThreadPool());
    
    static {
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                //clientSocketChannelFactory.releaseExternalResources();
            }
        }));
    }
    
    private final HttpRequestProcessor unproxiedRequestProcessor = 
        new HttpRequestProcessor() {
            final RelayPipelineFactoryFactory pf = 
                new DefaultRelayPipelineFactoryFactory(null, 
                    new HashMap<String, HttpFilter>(), null, 
                        new DefaultChannelGroup("HTTP-Proxy-Server"));
            private final HttpRequestHandler requestHandler =
                new HttpRequestHandler(clientSocketChannelFactory, pf);
            @Override
            public void processRequest(final Channel browserChannel,
                final ChannelHandlerContext ctx, final MessageEvent me) 
                throws IOException {
                requestHandler.messageReceived(ctx, me);
            }
            
            @Override
            public boolean hasProxy() {
                return false;
            }

            @Override
            public void processChunk(final ChannelHandlerContext ctx, 
                final MessageEvent me) throws IOException {
                requestHandler.messageReceived(ctx, me);
            }
            @Override
            public void close() {
            }
        };
    
    
    private final HttpRequestProcessor proxyRequestProcessor;
    
    private final HttpRequestProcessor anonymousPeerRequestProcessor;
    
    private final HttpRequestProcessor trustedPeerRequestProcessor;
    
    private final HttpRequestProcessor laeRequestProcessor;
    
    private HttpRequestProcessor currentRequestProcessor;

    private boolean readingChunks;

    /**
     * Specifies whether or not we're currently proxying requests. This is 
     * necessary because we don't have all the initial HTTP request data,
     * such as the referer or the URI, when we're processing HTTP chunks.
     */
    private boolean proxying;

    private final KeyStoreManager keyStoreManager;

    /**
     * Creates a new handler that reads incoming HTTP requests and dispatches
     * them to proxies as appropriate.
     * 
     * @param proxyProvider Providers for proxy addresses to connect to.
     * @param proxyStatusListener The class to notify of changes in the proxy
     * status.
     * @param whitelist The list of sites to proxy.
     * @param encryptingP2pClient The client for creating P2P connections.
     * @param keyStoreManager Keeps track of all trusted keys. 
     * @param clientSocketChannelFactory Factory for creating client channels.
     */
    public DispatchingProxyRelayHandler(final ProxyProvider proxyProvider,
        final ProxyStatusListener proxyStatusListener, 
        final XmppP2PClient encryptingP2pClient, 
        final KeyStoreManager keyStoreManager) {
        this.proxyProvider = proxyProvider;
        this.proxyStatusListener = proxyStatusListener;
        this.keyStoreManager = keyStoreManager;
        
        // This uses the raw p2p client because all traffic sent over these
        // connections already uses end-to-end encryption.
        this.anonymousPeerRequestProcessor =
            new PeerHttpConnectRequestProcessor(new Proxy() {
                @Override
                public InetSocketAddress getProxy() {
                    throw new UnsupportedOperationException(
                        "Peer proxy required");
                }
                @Override
                public URI getPeerProxy() {
                    // For CONNECT we can use either an anonymous peer or a
                    // trusted peer.
                    final URI lantern = proxyProvider.getAnonymousProxy();
                    if (lantern == null) {
                        return proxyProvider.getPeerProxy();
                    }
                    return lantern;
                }
            },  proxyStatusListener, encryptingP2pClient);
        
        this.trustedPeerRequestProcessor =
            new PeerHttpRequestProcessor(new Proxy() {
                @Override
                public InetSocketAddress getProxy() {
                    throw new UnsupportedOperationException(
                        "Peer proxy required");
                }
                @Override
                public URI getPeerProxy() {
                    return proxyProvider.getPeerProxy();
                }
            },  proxyStatusListener, encryptingP2pClient, this.keyStoreManager);
        
        this.proxyRequestProcessor =
            new DefaultHttpRequestProcessor(proxyStatusListener,
                new HttpRequestTransformer() {
                    @Override
                    public void transform(final HttpRequest request, 
                        final InetSocketAddress proxyAddress) {
                        // Does nothing.
                    }
                }, false,
                new Proxy() {
                    @Override
                    public URI getPeerProxy() {
                        throw new UnsupportedOperationException(
                            "Peer proxy not supported here.");
                    }
                    @Override
                    public InetSocketAddress getProxy() {
                        return proxyProvider.getProxy();
                    }
                }, this.keyStoreManager);
        this.laeRequestProcessor =
            new DefaultHttpRequestProcessor(proxyStatusListener,
                new LaeHttpRequestTransformer(), true,
                new Proxy() {
                    @Override
                    public URI getPeerProxy() {
                        throw new UnsupportedOperationException(
                            "Peer proxy not supported here.");
                    }
                    @Override
                    public InetSocketAddress getProxy() {
                        return proxyProvider.getLaeProxy();
                    }
            }, null);
    }

    @Override
    public void messageReceived(final ChannelHandlerContext ctx, 
        final MessageEvent me) {
        messagesReceived++;
        log.info("Received {} total messages", messagesReceived);
        if (!readingChunks) {
            log.info("Reading HTTP request (not a chunk)...");
            this.currentRequestProcessor = dispatchRequest(ctx, me);
        } 
        else {
            log.info("Reading chunks...");
            try {
                final HttpChunk chunk = (HttpChunk) me.getMessage();
                
                // Remember this will typically be a persistent connection, 
                // so we'll get another request after we're read the last 
                // chunk. So we need to reset it back to no longer read in 
                // chunk mode.
                if (chunk.isLast()) {
                    this.readingChunks = false;
                }
                this.currentRequestProcessor.processChunk(ctx, me);
            } catch (final IOException e) {
                // Unclear what to do here. If we couldn't connect to a remote
                // peer, for example, we don't want to close the connection
                // to the browser. If the other end closed the connection,
                // it could have been due to connection close rules, or it
                // could have been because they simply went offline.
                log.info("Exception processing chunk", e);
            }
        }
        log.info("Done processing HTTP request....");
    }
    
    private HttpRequestProcessor dispatchRequest(
        final ChannelHandlerContext ctx, final MessageEvent me) {
        final HttpRequest request = (HttpRequest)me.getMessage();
        final String uri = request.getUri();
        log.info("URI is: {}", uri);

        final String referer = request.getHeader("referer");
        
        final String uriToCheck;
        log.info("Referer: "+referer);
        if (!StringUtils.isBlank(referer)) {
            uriToCheck = referer;
        } else {
            uriToCheck = uri;
        }
        
        // We need to set this outside of proxying rules because we first
        // send incoming messages down chunked versus unchunked paths and
        // then send them down proxied versus unproxied paths.
        if (request.isChunked()) {
            readingChunks = true;
        } else {
            readingChunks = false;
        }
        
        this.proxying = Whitelist.isWhitelisted(uriToCheck);
        
        if (proxying) {
            // If it's an HTTP request, see if we can redirect it to HTTPS.
            final String https = HttpsEverywhere.toHttps(uri);
            if (!https.equals(uri)) {
                final HttpResponse response = 
                    new DefaultHttpResponse(request.getProtocolVersion(), 
                        HttpResponseStatus.MOVED_PERMANENTLY);
                response.setProtocolVersion(HttpVersion.HTTP_1_0);
                response.setHeader(HttpHeaders.Names.LOCATION, https);
                response.setHeader(HttpHeaders.Names.CONTENT_LENGTH, "0");
                log.info("Sending redirect response!!");
                browserToProxyChannel.write(response);
                ProxyUtils.closeOnFlush(browserToProxyChannel);
                // Note this redirect should result in a new HTTPS request 
                // coming in on this connection or a new connection -- in face
                // this redirect should always result in an HTTP CONNECT 
                // request as a result of the redirect. That new request
                // will not attempt to use the existing processor, so it's 
                // not an issue to return null here.
                return null;
            }
            log.info("Not converting to HTTPS");
            LanternHub.statsTracker().incrementProxiedRequests();
            return dispatchProxyRequest(ctx, me);
        } else {
            log.info("Not proxying!");
            LanternHub.statsTracker().incrementDirectRequests();
            try {
                this.unproxiedRequestProcessor.processRequest(
                    browserToProxyChannel, ctx, me);
            } catch (final IOException e) {
                // This should not happen because the underlying Netty handler
                // does not throw an exception.
                log.warn("Could not handle unproxied request -- " +
                    "should never happen", e);
            }
            return this.unproxiedRequestProcessor;
        }
    }
    
    private HttpRequestProcessor dispatchProxyRequest(
        final ChannelHandlerContext ctx, final MessageEvent me) {
        final HttpRequest request = (HttpRequest) me.getMessage();
        log.info("Dispatching request");
        if (request.getMethod() == HttpMethod.CONNECT) {
            if (ANONYMOUS_ACTIVE && this.anonymousPeerRequestProcessor.hasProxy()) {
                try {
                    this.anonymousPeerRequestProcessor.processRequest(
                        browserToProxyChannel, ctx, me);
                    log.info("Processed CONNECT on peer...returning");
                    return null;
                } catch (final IOException e) {
                    log.warn("Could not send CONNECT to anonymous proxy", e);
                    // This will happen whenever the server's giving us bad
                    // anonymous proxies, which could happen quite often.
                    // We should fall back to central.
                    centralConnect(request);
                    return null;
                }
            } else {
                // We need to forward the CONNECT request from this proxy to an
                // external proxy that can handle it. We effectively want to 
                // relay all traffic in this case without doing anything on 
                // our own other than direct the CONNECT request to the correct 
                // proxy.
                centralConnect(request);
                return null;
            }
        }
        
        if (LAE_ACTIVE && isLae(request) && this.laeRequestProcessor.hasProxy()) {
            try {
                this.laeRequestProcessor.processRequest(browserToProxyChannel, 
                    ctx, me);
                return this.laeRequestProcessor;
            } catch (final IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        } 
        if (TRUSTED_ACTIVE && this.trustedPeerRequestProcessor.hasProxy())  {
            try {
                this.trustedPeerRequestProcessor.processRequest(
                    browserToProxyChannel, ctx, me);
                return this.trustedPeerRequestProcessor;
            } catch (final IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        } 
        if (PROXIES_ACTIVE && this.proxyRequestProcessor.hasProxy()) {
            log.info("Using standard proxy");
            try {
                this.proxyRequestProcessor.processRequest(
                    browserToProxyChannel, ctx, me);
                return this.proxyRequestProcessor;
            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }
        
        // Not much we can do if no proxy can handle it.
        return null;
    }

    private void centralConnect(final HttpRequest request) {
        if (this.httpConnectChannelFuture == null) {
            log.info("Opening HTTP CONNECT tunnel");
            try {
                this.httpConnectChannelFuture = 
                    openOutgoingRelayChannel(request);
            } catch (final IOException e) {
                log.error("Could not open CONNECT channel", e);
            }
        } else {
            log.error("Outbound channel already assigned?");
        }
    }

    private boolean isLae(final HttpRequest request) {
        final String uri = request.getUri();
        if (uri.contains("youtube.com")) {
            log.info("NOT USING LAE FOR YOUTUBE");
            return false;
        }
        final HttpMethod method = request.getMethod();
        if (method == HttpMethod.GET) {
            return true;
        }
        if (method == HttpMethod.CONNECT) {
            return false;
        }
        if (LanternUtils.isTransferEncodingChunked(request)) {
            return false;
        }
        // From http://code.google.com/appengine/docs/quotas.html, we cannot
        // send requests larger than 10MB. 
        if (method == HttpMethod.POST) {
            final String contentLength = 
                request.getHeader(Names.CONTENT_LENGTH);
            if (StringUtils.isBlank(contentLength)) {
                // If it's a post without a content length, we want to be 
                // cautious.
                return false;
            }
            final long cl = Long.parseLong(contentLength);
            if (cl > REQUEST_SIZE_LIMIT) {
                return false;
            }
            return true;
        }
        return false;
    }

    @Override
    public void channelOpen(final ChannelHandlerContext ctx, 
        final ChannelStateEvent e) {
        log.info("Got incoming channel");
        this.browserToProxyChannel = e.getChannel();
    }
    
    private ChannelFuture openOutgoingRelayChannel(final HttpRequest request) 
        throws IOException {
        this.browserToProxyChannel.setReadable(false);

        // Start the connection attempt.
        final ClientBootstrap cb = 
            new ClientBootstrap(LanternUtils.clientSocketChannelFactory);
        
        final ChannelPipeline pipeline = cb.getPipeline();
        
        // This is slightly odd, as we tunnel SSL inside SSL, but we'd 
        // otherwise just be running an open CONNECT proxy.
        
        // It's also necessary to use our own engine here, as we need to trust
        // the cert from the proxy.
        final LanternClientSslContextFactory sslFactory =
            new LanternClientSslContextFactory(this.keyStoreManager);
        final SSLEngine engine =
            sslFactory.getClientContext().createSSLEngine();
        engine.setUseClientMode(true);
        pipeline.addLast("ssl", new SslHandler(engine));
        pipeline.addLast("encoder", new HttpRequestEncoder());
        pipeline.addLast("handler", 
            new StatsTrackingHttpConnectRelayingHandler(
                this.browserToProxyChannel));
        log.info("Connecting to relay proxy");
        final InetSocketAddress isa = this.proxyProvider.getProxy();
        if (isa == null) {
            log.error("NO PROXY AVAILABLE?");
            ProxyUtils.closeOnFlush(browserToProxyChannel);
            throw new IOException("No proxy to use for CONNECT?");
        }
        final ChannelFuture cf = cb.connect(isa);
        log.info("Got an outbound channel on: {}", hashCode());
        
        final ChannelPipeline browserPipeline = 
            browserToProxyChannel.getPipeline();
        browserPipeline.remove("encoder");
        browserPipeline.remove("decoder");
        browserPipeline.remove("handler");
        browserPipeline.addLast("handler", 
            new HttpConnectRelayingHandler(cf.getChannel(), null));
        
        // This is handy, as set readable to false while the channel is 
        // connecting ensures we won't get any incoming messages until
        // we're fully connected.
        cf.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(final ChannelFuture future) 
                throws Exception {
                if (future.isSuccess()) {
                    cf.getChannel().write(request).addListener(
                        new ChannelFutureListener() {
                            @Override
                            public void operationComplete(
                                final ChannelFuture channelFuture) 
                                throws Exception {
                                // we're using HTTP connect here, so we need
                                // to remove the encoder and start reading
                                // from the inbound channel only when we've
                                // used the original encoder to properly encode
                                // the CONNECT request.
                                pipeline.remove("encoder");
                                
                                // Begin to accept incoming traffic.
                                browserToProxyChannel.setReadable(true);
                            }
                    });
                    
                } else {
                    // Close the connection if the connection attempt has failed.
                    browserToProxyChannel.close();
                    proxyStatusListener.onCouldNotConnect(isa);
                }
            }
        });
        
        return cf;
    }

    @Override 
    public void channelClosed(final ChannelHandlerContext ctx, 
        final ChannelStateEvent e) {
        log.info("Got inbound channel closed. Closing outbound.");
        this.trustedPeerRequestProcessor.close();
        this.anonymousPeerRequestProcessor.close();
        this.proxyRequestProcessor.close();
        this.laeRequestProcessor.close();
    }
    
    @Override
    public void exceptionCaught(final ChannelHandlerContext ctx, 
        final ExceptionEvent e) throws Exception {
        log.error("Caught exception on INBOUND channel", e.getCause());
        ProxyUtils.closeOnFlush(this.browserToProxyChannel);
    }
    
}
