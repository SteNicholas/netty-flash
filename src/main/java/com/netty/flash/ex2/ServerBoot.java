package com.netty.flash.ex2;

/**
 * 服务端启动器:
 * 1.服务端监听端口;
 * 2.客户端连接服务端;
 * 3.客户端给服务端发送数据
 * <p>
 * 服务端与客户端通信过程:
 * 1.监听端口[服务端监听端口]->NioEventLoop[NIO事件循环:新连接的接入、连接当前存在的连接，连接上数据流读写]:(1)Server在端口上监听Client新用户连接,(2)新用户链接建立完成后在对应端口上监听新连接的数据;
 * 2.新连接[通过while(true)循环不断accept()方法连接,客户端创建新连接,服务端获取客户端连接，处理器处理客户端连接]->Channel;
 * 3.接收数据[客户端写数据到服务端,服务端接收数据]->ByteBuf[服务端接收客户端数据流载体];
 * 4.业务逻辑[服务端接收到客户端数据做业务逻辑处理]->Pipeline:一系列ChannelHandler[二进制协议数据包拆分,数据包Java对象转换,真正业务逻辑处理,封数据库包];
 * 5.发送数据[服务端发送数据到客户端]->ByteBuf[服务端发送客户端数据流载体]
 * <p>
 * Netty基本组件:
 * NioEventLoop->Thread:accept/getOutputStream->select,ClientHandler.start->processSelectedKey
 * Channel->Socket/ServerSocketChannel->ServerSocket:NioByteUnsafe[客户端Channel数据流读写],NioMessageUnsafe[客户端新连接接入,处理新连接使用NioServerSocketChannel,javaChannel()获取Nio的ServerSocketChannel]
 * ByteBuf->IO Bytes:read/write->输入/输出流
 * Pipeline->Logic Chain:AbstractChannel的构造器使用newChannelPipeline()创建Pipeline,每个Channel都有Pipeline,将Login Chain逻辑链路加到Channel,Channel数据流读写都经过Pipeline
 * ChannelHandler->Logic:DefaultChannelPipeline的add/remove方法动态增加/删除Logic,数据流读写都经过ChannelHandler
 */
public class ServerBoot {
    private static final int PORT = 8000;

    public static void main(String[] args) {
        Server server = new Server(PORT);
        server.start();
    }
}