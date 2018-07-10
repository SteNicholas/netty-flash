package com.netty.flash.ex11;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.ByteProcessor;

/**
 * Netty设计模式的应用:
 * 迭代器模式:实现内存零拷贝
 * 1.迭代器接口
 * 2.对容器里面各个对象进行访问
 * 应用范例:
 * @see io.netty.buffer.CompositeByteBuf
 * _getByte()获取索引index位置的组件Component,底层组件的buf调用getByte()获取ByteBuf
 */

/**
 * Netty设计模式的应用:
 * 责任链模式:使得多个对象都有机会处理同一请求,从而避免请求的发送者和接收者之间的耦合关系,将这些对象连成一条链并且沿着这条链传递这个请求直到有个对象处理它为止,处理过程中每个对象处理它所关心的部分,其中有个对象发现它不适合把事件往下进行传播随时终止传播
 * 1.责任处理器接口:责任链每道关卡,每道关卡对一个请求进行相应处理
 * 2.创建链,添加删除责任处理器接口
 * 3.上下文:责任处理器处理事件感知上下文,通过上下文获取它对应需要的对象
 * 4.责任终止机制:每个责任处理器有权终止事件继续传播
 * Head=A=B=C=Tail 责任链
 *            |
 *    责任处理器
 * @see io.netty.channel.ChannelPipeline
 * 责任链:ChannelPipeline,责任处理器:ChannelHandler[ChannelInboundHandler/ChannelOutboundHandler],上下文:ChannelHandlerContext,事件传播:fire***()->findContextInbound()->ctx = ctx.next
 */
public class IterableTest {

    public static void main(String[] args) {
        ByteBuf header = Unpooled.wrappedBuffer(new byte[]{1, 2, 3});
        ByteBuf body = Unpooled.wrappedBuffer(new byte[]{4, 5, 6});

        ByteBuf merge = merge(header, body);
        merge.forEachByte(new ByteProcessor() {
            @Override
            public boolean process(byte value) throws Exception {
                System.out.println(value);
                return true;
            }
        });
    }

    public static ByteBuf merge(ByteBuf header, ByteBuf body) {
        //ByteBuf byteBuf = ByteBufAllocator.DEFAULT.ioBuffer();
        //byteBuf.writeBytes(header);
        //byteBuf.writeBytes(body);
        //实现零拷贝
        CompositeByteBuf byteBuf = ByteBufAllocator.DEFAULT.compositeBuffer(2);
        byteBuf.addComponent(true, header);
        byteBuf.addComponent(true, body);
        return byteBuf;
    }
}