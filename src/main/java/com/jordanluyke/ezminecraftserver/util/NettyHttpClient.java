package com.jordanluyke.ezminecraftserver.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
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
import io.reactivex.Single;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * @author Jordan Luyke <jordanluyke@gmail.com>
 */
public class NettyHttpClient {
    private static final Logger logger = LogManager.getLogger(NettyHttpClient.class);

    public static Single<ClientResponse> get(String url) {
        return get(url, Collections.emptyMap());
    }

    public static Single<ClientResponse> get(String url, Map<String, Object> params) {
        return get(url, params, Collections.emptyMap());
    }

    public static Single<ClientResponse> get(String url, Map<String, Object> params, Map<String, String> headers) {
        String _url = params.size() > 0 ? url + "?" + toQuerystring(params) : url;
        return request(_url, HttpMethod.GET, new byte[0], headers);
    }

    public static Single<ClientResponse> post(String url) {
        return post(url, Collections.emptyMap());
    }

    public static Single<ClientResponse> post(String url, Map<String, Object> body) {
        return post(url, body, Collections.emptyMap());
    }

    public static Single<ClientResponse> post(String url, Map<String, Object> body, Map<String, String> headers) {
        return request(url, HttpMethod.POST, body, headers);
    }

    public static Single<ClientResponse> put(String url) {
        return put(url, Collections.emptyMap());
    }

    public static Single<ClientResponse> put(String url, Map<String, Object> body) {
        return put(url, body, Collections.emptyMap());
    }

    public static Single<ClientResponse> put(String url, Map<String, Object> body, Map<String, String> headers) {
        return request(url, HttpMethod.PUT, body, headers);
    }

    public static Single<ClientResponse> delete(String url) {
        return delete(url, Collections.emptyMap());
    }

    public static Single<ClientResponse> delete(String url, Map<String, Object> body) {
        return delete(url, body, Collections.emptyMap());
    }

    public static Single<ClientResponse> delete(String url, Map<String, Object> body, Map<String, String> headers) {
        return request(url, HttpMethod.DELETE, body, headers);
    }

    public static Single<ClientResponse> request(String url, HttpMethod method, Map<String, Object> body, Map<String, String> headers) {
        return request(url, method, bodyToBytes(body, headers), headers);
    }

    public static Single<ClientResponse> request(String url, HttpMethod method, byte[] body, Map<String, String> headers) {
        return Single.defer(() -> {
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
                            pipeline.addLast(new SimpleChannelInboundHandler<HttpObject>() {
                                HttpResponse response;
                                Timer timer;
                                ByteBuf data = Unpooled.buffer();

                                @Override
                                public void channelRead0(ChannelHandlerContext ctx, HttpObject msg) {
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
                                            if(timer != null) {
                                                timer.cancel();
                                                timer.purge();
                                                logger.info("Download complete");
                                            }

                                            res.setRawBody(data.array());
                                            data.release();
                                            ctx.close();
                                        }
                                    }
                                }

                                @Override
                                public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                                }

                                private boolean isBinaryFile(HttpHeaders httpHeaders) {
                                    return httpHeaders.contains(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_OCTET_STREAM, true);
                                }
                            });
                        }
                    });

            return getChannel(bootstrap.connect(uri.getHost(), uri.getPort()))
                    .flatMap(channel -> {
                        ByteBuf content = Unpooled.copiedBuffer(body);
                        DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, method, uri.toString(), content);
                        request.headers().set(HttpHeaderNames.HOST, uri.getHost());
                        request.headers().set(HttpHeaderNames.ACCEPT_ENCODING, HttpHeaderValues.GZIP);
                        request.headers().set(HttpHeaderNames.CONTENT_TYPE, headers.getOrDefault(HttpHeaderNames.CONTENT_TYPE.toString(), HttpHeaderValues.APPLICATION_JSON.toString()));
                        request.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes());
                        headers.forEach((key, value) -> request.headers().set(key, value));
                        channel.config().setConnectTimeoutMillis((int) TimeUnit.SECONDS.toMillis(2));
                        return getChannel(channel.writeAndFlush(request));
                    })
                    .flatMap(channel -> getChannel(channel.closeFuture()))
                    .flatMap(Void -> {
                        if(res.getRawBody() == null || res.getStatusCode() == -1)
                            return Single.error(new RuntimeException("Bad response"));
                        return Single.just(res);
                    })
                    .doFinally(group::shutdownGracefully);
        });
    }

    private static Single<Channel> getChannel(ChannelFuture channelFuture) {
        CompletableFuture<Channel> completableFuture = new CompletableFuture<>();
        channelFuture.addListener((ChannelFuture future) -> {
            if(future.isSuccess())
                completableFuture.complete(future.channel());
            else
                completableFuture.completeExceptionally(future.cause());
        });
        return Single.fromFuture(completableFuture);
    }

    private static byte[] bodyToBytes(Map<String, Object> body, Map<String, String> headers) {
        if(body.size() == 0)
            return new byte[0];
        String contentType = headers.get(HttpHeaderNames.CONTENT_TYPE.toString());
        if(contentType == null || contentType.equals(HttpHeaderValues.APPLICATION_JSON.toString())) {
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
    @ToString
    public static class ClientResponse {
        private int statusCode;
        private byte[] rawBody;
        private Map<String, String> headers = new HashMap<>();

        public String getBodyString() {
            if(rawBody == null)
                return null;
            return new String(rawBody, StandardCharsets.UTF_8);
        }

        public JsonNode getBodyJson() {
            try {
                return new ObjectMapper().readTree(rawBody);
            } catch(IOException e) {
                throw new RuntimeException("Unable to parse json");
            }
        }
    }
}