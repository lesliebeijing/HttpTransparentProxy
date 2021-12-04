package com.lesliefang.httpproxy;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;

public class HttpProxyClientHandler extends ChannelInboundHandlerAdapter {
    private String host;
    private int port;
    private boolean isConnectMethod = false;
    // 客户端到代理的 channel
    private Channel clientChannel;
    // 代理到远端服务器的 channel
    private Channel remoteChannel;

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        clientChannel = ctx.channel();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof FullHttpRequest) {
            FullHttpRequest httpRequest = (FullHttpRequest) msg;
            System.out.println(httpRequest);
            isConnectMethod = HttpMethod.CONNECT.equals(httpRequest.method());

            // 解析目标主机host和端口号
            parseHostAndPort(httpRequest);

            System.out.println("remote server is " + host + ":" + port);

            // disable AutoRead until remote connection is ready
            clientChannel.config().setAutoRead(false);

            /**
             * 建立代理服务器到目标主机的连接
             */
            Bootstrap b = new Bootstrap();
            b.group(clientChannel.eventLoop()) // 和 clientChannel 使用同一个 EventLoop
                    .channel(clientChannel.getClass())
                    .handler(new HttpRequestEncoder());
            ChannelFuture f = b.connect(host, port);
            remoteChannel = f.channel();
            f.addListener((ChannelFutureListener) future -> {
                if (future.isSuccess()) {
                    // connection is ready, enable AutoRead
                    clientChannel.config().setAutoRead(true);

                    if (isConnectMethod) {
                        // CONNECT 请求回复连接建立成功
                        HttpResponse connectedResponse = new DefaultHttpResponse(httpRequest.protocolVersion(), new HttpResponseStatus(200, "Connection Established"));
                        clientChannel.writeAndFlush(connectedResponse);
                    } else {
                        // 普通http请求解析了第一个完整请求，第一个请求也要原样发送到远端服务器
                        remoteChannel.writeAndFlush(httpRequest);
                    }

                    /**
                     * 第一个完整Http请求处理完毕后，不需要解析任何 Http 数据了，直接盲目转发 TCP 流就行了
                     * 所以无论是连接客户端的 clientChannel 还是连接远端主机的 remoteChannel 都只需要一个 RelayHandler 就行了。
                     * 代理服务器在中间做转发。
                     *
                     * 客户端   --->  clientChannel --->  代理 ---> remoteChannel ---> 远端主机
                     * 远端主机 --->  remoteChannel  --->  代理 ---> clientChannel ---> 客户端
                     */
                    clientChannel.pipeline().remove(HttpRequestDecoder.class);
                    clientChannel.pipeline().remove(HttpResponseEncoder.class);
                    clientChannel.pipeline().remove(HttpObjectAggregator.class);
                    clientChannel.pipeline().remove(HttpProxyClientHandler.this);
                    clientChannel.pipeline().addLast(new RelayHandler(remoteChannel));

                    remoteChannel.pipeline().remove(HttpRequestEncoder.class);
                    remoteChannel.pipeline().addLast(new RelayHandler(clientChannel));
                } else {
                    clientChannel.close();
                }
            });
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        flushAndClose(remoteChannel);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        cause.printStackTrace();
        flushAndClose(ctx.channel());
    }

    private void flushAndClose(Channel ch) {
        if (ch != null && ch.isActive()) {
            ch.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
        }
    }

    /**
     * 解析header信息，建立连接
     * HTTP 请求头如下
     * GET http://www.baidu.com/ HTTP/1.1
     * Host: www.baidu.com
     * User-Agent: curl/7.69.1
     * Proxy-Connection:Keep-Alive
     * ---------------------------
     * HTTPS请求头如下
     * CONNECT www.baidu.com:443 HTTP/1.1
     * Host: www.baidu.com:443
     * User-Agent: curl/7.69.1
     * Proxy-Connection: Keep-Alive
     */
    private void parseHostAndPort(HttpRequest httpRequest) {
        String hostAndPortStr;
        if (isConnectMethod) {
            // CONNECT 请求以请求行为准
            hostAndPortStr = httpRequest.uri();
        } else {
            hostAndPortStr = httpRequest.headers().get("Host");
        }
        String[] hostPortArray = hostAndPortStr.split(":");
        host = hostPortArray[0];
        if (hostPortArray.length == 2) {
            port = Integer.parseInt(hostPortArray[1]);
        } else if (isConnectMethod) {
            // 没有端口号，CONNECT 请求默认443端口
            port = 443;
        } else {
            // 没有端口号，普通HTTP请求默认80端口
            port = 80;
        }
    }
}
