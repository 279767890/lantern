package org.lantern;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.concurrent.ConcurrentHashMap;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.handler.ssl.SslHandler;
import org.jboss.netty.handler.traffic.GlobalTrafficShapingHandler;
import org.jboss.netty.util.Timer;
import org.lantern.event.Events;
import org.lantern.event.IncomingPeerEvent;
import org.lantern.util.Netty3LanternTrafficCounterHandler;
import org.littleshoot.proxy.HandshakeHandler;
import org.littleshoot.proxy.HandshakeHandlerFactory;
import org.littleshoot.proxy.SslHandshakeHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * This class handles intercepting incoming SSL connections to the HTTP proxy
 * server, associating incoming client certificates with their associated
 * peers. Any incoming connections from non-trusted peers with non-trusted
 * certificates will be rejected.
 */
@Singleton
public class CertTrackingSslHandlerFactory implements HandshakeHandlerFactory,
    Shutdownable {

    private final Logger log = LoggerFactory.getLogger(getClass());

    /**
     * This is the ID used for the per-peer counters in the pipeline.
     */
    public static final String PIPELINE_ID = "trafficHandler";
    
    private final ConcurrentHashMap<InetAddress, Netty3LanternTrafficCounterHandler> handlers =
            new ConcurrentHashMap<InetAddress, Netty3LanternTrafficCounterHandler>();
    
    private final LanternTrustStore trustStore;

    private final Timer timer;
    
    @Inject
    public CertTrackingSslHandlerFactory(final Timer timer,
        final LanternTrustStore trustStore) {
        this.timer = timer;
        this.trustStore = trustStore;
    }
    
    @Override
    public void stop() {
        for (final GlobalTrafficShapingHandler handler : this.handlers.values()) {
            handler.releaseExternalResources();
        }
    }

    /**
     * This method is called for every new pipeline that's created -- i.e.
     * for every new incoming connection to the server. We need to reload
     * the trust store each time to make sure we take into account all the last
     * certificates from peers.
     */
    @Override
    public HandshakeHandler newHandshakeHandler() {
        log.debug("Creating new handshake handler...");
        
        final CertTrackingTrustManager certTracker = 
            new CertTrackingTrustManager(trustStore);
        final SSLEngine engine = getServerContext(certTracker).createSSLEngine();
        engine.setUseClientMode(false);
        engine.setNeedClientAuth(true);
        final SslHandlerInterceptor handler = 
            new SslHandlerInterceptor(engine, certTracker);
        
        certTracker.setSslHandler(handler);
        return new SslHandshakeHandler("ssl", handler);
    }
    
    public SSLContext getServerContext(final TrustManager trustManager) {
        try {
            final SSLContext context = SSLContext.getInstance("TLS");
            context.init(trustStore.getKeyManagerFactory().getKeyManagers(), 
                new TrustManager[]{trustManager}, null);
            return context;
        } catch (final Exception e) {
            throw new Error(
                    "Failed to initialize the client-side SSLContext", e);
        }
    }
    
    private class CertTrackingTrustManager implements X509TrustManager {

        private final Logger log = LoggerFactory.getLogger(getClass());
        
        private final LanternTrustStore trustStore;

        private SslHandlerInterceptor handler;

        private CertTrackingTrustManager(
            final LanternTrustStore trustStore) {
            this.trustStore = trustStore;
        }
        
        public void setSslHandler(final SslHandlerInterceptor handler) {
            this.handler = handler;
            
        }

        @Override
        public void checkClientTrusted(final X509Certificate[] chain, String arg1)
                throws CertificateException {
            final X509Certificate cert = chain[0];
            if (!trustStore.containsCertificate(cert)) {
                throw new CertificateException("not trusted");
            }
            
            log.debug("Certificate trusted");
            // We should already know about the peer at this point, and it's just
            // a matter of correlating that peer with this certificate and 
            // connection.
            
            Events.asyncEventBus().post(
                new IncomingPeerEvent(this.handler.channel, this.handler.trafficCounter, cert));
            
        }

        @Override
        public void checkServerTrusted(final X509Certificate[] chain, String arg1)
                throws CertificateException {
            throw new CertificateException(
                    "Should never be checking server trust from the server");
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            // We don't accept any issuers.
            return new X509Certificate[]{};
        }
    }
    
    private final class SslHandlerInterceptor extends SslHandler {

        private Channel channel;
        private Netty3LanternTrafficCounterHandler trafficCounter;

        public SslHandlerInterceptor(final SSLEngine engine, 
            final CertTrackingTrustManager certTracker) {
            super(engine);
            certTracker.setSslHandler(this);
        }
        
        @Override
        public void channelOpen(final ChannelHandlerContext ctx, 
            final ChannelStateEvent e) throws Exception {
            // Just record the channel
            this.channel = ctx.getChannel();
            super.channelOpen(ctx, e);
        }
        
        @Override
        public void channelConnected(final ChannelHandlerContext ctx, 
            final ChannelStateEvent e) throws Exception {
            
            try {
                
                // We basically want to add separate traffic handlers per IP, and
                // we do that here. We have a new incoming socket and check for an
                // existing handler. If it's there, we use it. Otherwise we add and
                // use a new one.
                final InetSocketAddress isa = 
                    (InetSocketAddress) ctx.getChannel().getRemoteAddress();
                final InetAddress address = isa.getAddress();
                
                final Netty3LanternTrafficCounterHandler newHandler = 
                        new Netty3LanternTrafficCounterHandler(timer, true);
                final Netty3LanternTrafficCounterHandler existingHandler =
                        handlers.putIfAbsent(address, newHandler);
                
                if (existingHandler == null) {
                    this.trafficCounter = newHandler;
                } else {
                    this.trafficCounter = existingHandler;
                }
                ctx.getChannel().getPipeline().addFirst(PIPELINE_ID, 
                    this.trafficCounter);
            } finally {
                // The message is then just passed to the next handler
                super.channelConnected(ctx, e);
            }
        }
    }
}
