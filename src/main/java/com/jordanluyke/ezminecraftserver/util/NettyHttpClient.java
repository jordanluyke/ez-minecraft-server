package com.jordanluyke.ezminecraftserver.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.reactivex.Observable;
import lombok.Getter;
import lombok.Setter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.net.ssl.SSLException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.HashMap;
import java.util.stream.Collectors;

/**
 * @author Jordan Luyke <jordanluyke@gmail.com>
 */
public class NettyHttpClient {
    private static final Logger logger = LogManager.getLogger(NettyHttpClient.class);

    public static Observable<ClientResponse> get(String url) {
        return get(url, Collections.emptyMap());
    }

    public static Observable<ClientResponse> get(String url, Map<String, Object> params) {
        return get(url, params, Collections.emptyMap());
    }

    public static Observable<ClientResponse> get(String url, Map<String, Object> params, Map<String, String> headers) {
        String _url = params.size() > 0 ? url + "?" + toQuerystring(params) : url;
        return request(_url, HttpMethod.GET, new byte[0], headers);
    }

    public static Observable<ClientResponse> post(String url) {
        return post(url, Collections.emptyMap());
    }

    public static Observable<ClientResponse> post(String url, Map<String, Object> body) {
        return post(url, body, Collections.emptyMap());
    }

    public static Observable<ClientResponse> post(String url, Map<String, Object> body, Map<String, String> headers) {
        return request(url, HttpMethod.POST, body, headers);
    }

    public static Observable<ClientResponse> put(String url) {
        return put(url, Collections.emptyMap());
    }

    public static Observable<ClientResponse> put(String url, Map<String, Object> body) {
        return put(url, body, Collections.emptyMap());
    }

    public static Observable<ClientResponse> put(String url, Map<String, Object> body, Map<String, String> headers) {
        return request(url, HttpMethod.PUT, body, headers);
    }

    public static Observable<ClientResponse> delete(String url) {
        return delete(url, Collections.emptyMap());
    }

    public static Observable<ClientResponse> delete(String url, Map<String, Object> body) {
        return delete(url, body, Collections.emptyMap());
    }

    public static Observable<ClientResponse> delete(String url, Map<String, Object> body, Map<String, String> headers) {
        return request(url, HttpMethod.DELETE, body, headers);
    }

    public static Observable<ClientResponse> request(String url, HttpMethod method, Map<String, Object> body, Map<String, String> headers) {
        return request(url, method, bodyToBytes(body, headers), headers);
    }

    public static Observable<ClientResponse> request(String url, HttpMethod method, byte[] body, Map<String, String> headers) {
        URI uri;
        try {
            URI u = new URI(url);
            uri = new URI(u.getScheme(),
                    null,
                    u.getHost(),
                    HttpScheme.HTTPS.name().toString().equals(u.getScheme()) ? HttpScheme.HTTPS.port() : HttpScheme.HTTP.port(),
                    u.getPath(),
                    u.getQuery(),
                    null);
        } catch(URISyntaxException e) {
            throw new RuntimeException(e.getMessage());
        }

        SslContext sslCtx;
        try {
            if(uri.getPort() == HttpScheme.HTTPS.port())
                sslCtx = SslContextBuilder.forClient()
                    .trustManager(InsecureTrustManagerFactory.INSTANCE).build();
            else
                sslCtx = null;
        } catch (SSLException e) {
            throw new RuntimeException(e.getMessage());
        }

        EventLoopGroup group = new NioEventLoopGroup();
        ClientResponse res = new ClientResponse();
        Bootstrap bootstrap = new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {
                        ChannelPipeline pipeline = channel.pipeline();
                        if(sslCtx != null)
                            pipeline.addLast(sslCtx.newHandler(channel.alloc()));
                        pipeline.addLast(new HttpClientCodec());
                        pipeline.addLast(new HttpContentDecompressor());
                        pipeline.addLast(new SimpleChannelInboundHandler() {
                            HttpResponse response;
                            ByteBuf data = Unpooled.buffer();
                            Timer timer;

                            @Override
                            public void channelRead0(ChannelHandlerContext ctx, Object msg) {
                                if(msg instanceof HttpResponse) {
                                    response = (HttpResponse) msg;

                                    if(isBinaryFile(response.headers())) {
                                        long contentLength = HttpUtil.getContentLength(response);
                                        logger.info("Downloading: {}", url);
                                        timer = new Timer();
                                        timer.schedule(new TimerTask() {
                                            @Override
                                            public void run() {
                                                int percent = (int) (((double) data.readableBytes() / contentLength) * 100);
                                                logger.info("Progress: {}%", percent);
                                            }
                                        }, 0, 3000);
                                    }

                                    res.setStatusCode(response.status().code());
                                    res.setHeaders(response.headers()
                                            .entries()
                                            .stream()
                                            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
                                } else if(msg instanceof HttpContent) {
                                    HttpContent content = (HttpContent) msg;
                                    data = Unpooled.copiedBuffer(data, content.content());

                                    if(content instanceof LastHttpContent) {
                                        if(isBinaryFile(response.headers())) {
                                            timer.cancel();
                                            timer.purge();
                                            logger.info("Download complete");
                                        }

                                        res.setRawBody(data.array());
                                    }
                                }
                            }

                            private boolean isBinaryFile(HttpHeaders httpHeaders) {
                                return httpHeaders.contains(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_OCTET_STREAM, true);
                            }
                        });
                    }
                });

        return channelFutureToObservable(bootstrap.connect(uri.getHost(), uri.getPort()))
                .flatMap(channel -> {
                    String path = uri.getRawPath();
                    if(uri.getQuery() != null)
                        path += "?" + uri.getQuery();
                    ByteBuf content = Unpooled.copiedBuffer(body);
                    HttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, method, path, content);
                    request.headers().set(HttpHeaderNames.HOST, uri.getHost());
                    request.headers().set(HttpHeaderNames.ACCEPT_ENCODING, HttpHeaderValues.GZIP);
                    request.headers().set(HttpHeaderNames.CONTENT_TYPE, headers.getOrDefault(HttpHeaderNames.CONTENT_TYPE.toString(), HttpHeaderValues.APPLICATION_JSON.toString()));
                    request.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes());
                    headers.forEach((key, value) -> request.headers().set(key, value));
                    return channelFutureToObservable(channel.writeAndFlush(request));
                })
                .flatMap(channel -> channelFutureToObservable(channel.closeFuture()))
                .flatMap(Void -> {
                    if(res.getRawBody() == null)
                        return Observable.error(new RuntimeException("body is null"));
                    if(res.getStatusCode() == -1)
                        return Observable.error(new RuntimeException("statusCode is null"));
                    return Observable.just(res);
                })
                .doOnNext(Void -> group.shutdownGracefully());
    }

    private static Observable<Channel> channelFutureToObservable(ChannelFuture channelFuture) {
        return Observable.create(observer -> {
            channelFuture.addListener(future -> {
                if(future.isSuccess()) {
                    observer.onNext(channelFuture.channel());
                    observer.onComplete();
                } else
                    observer.onError(future.cause());
            });
        });
    }

    private static byte[] bodyToBytes(Map<String, Object> body, Map<String, String> headers) {
        if(body.size() == 0)
            return new byte[0];
        String contentType = headers.get(HttpHeaderNames.CONTENT_TYPE.toString());
        if(contentType.equals(HttpHeaderValues.APPLICATION_JSON.toString())) {
            try {
                return new ObjectMapper().writeValueAsBytes(body);
            } catch(JsonProcessingException e) {
                throw new RuntimeException(e.getMessage());
            }
        }
        return toQuerystring(body).getBytes();
    }

    private static String toQuerystring(Map<String, Object> params) {
        return params.entrySet()
                .stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue().toString()))
                .collect(Collectors.joining("&"));
    }

    private static String encode(String s) {
        try {
            return URLEncoder.encode(s, StandardCharsets.UTF_8.name());
        } catch(UnsupportedEncodingException e) {
            throw new RuntimeException(e.getMessage());
        }
    }

    @Getter
    @Setter
    public static class ClientResponse {
        private int statusCode;
        private byte[] rawBody;
        private Map<String, String> headers = new HashMap<>();

        public String getBody() {
            return new String(rawBody, StandardCharsets.UTF_8);
        }
    }
}