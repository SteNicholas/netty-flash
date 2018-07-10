package com.netty.flash.ex12.thread;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

import java.util.concurrent.ThreadLocalRandom;

@ChannelHandler.Sharable
public class ServerBusinessHandler extends SimpleChannelInboundHandler<ByteBuf> {

    public static final ChannelHandler INSTANCE = new ServerBusinessHandler();

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) throws Exception {
        ByteBuf data = Unpooled.directBuffer();
        data.writeBytes(msg);
        //数据库或者网络阻塞当前Reactor管理的所有连接,响应时间变长,Reactor线程被堵住则Reactor线程上其他所有的连接都会受到影响
        Object result = getResult(data);
        ctx.channel().writeAndFlush(result);
    }

    private Object getResult(ByteBuf data) {
        //90.0% == 1ms
        //95.0% == 10ms     1000 50 > 10ms
        //99.0% == 100ms   1000 10 > 100ms
        //99.9% == 1000ms 1000 1   > 1000ms
        int level = ThreadLocalRandom.current().nextInt(1, 1000);

        int time;
        if (level <= 900) {
            time = 1;
        } else if (level <= 950) {
            time = 10;
        } else if (level <= 990) {
            time = 100;
        } else {
            time = 1000;
        }

        try {
            Thread.sleep(time);
        } catch (InterruptedException e) {
        }

        return data;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        // ignore
    }
}