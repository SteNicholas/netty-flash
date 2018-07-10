package com.netty.flash.ex6;

import com.netty.flash.ex6.exceptionspread.*;

import com.netty.flash.ex6.exceptionspread.InBoundHandlerA;
import com.netty.flash.ex6.exceptionspread.InBoundHandlerB;
import com.netty.flash.ex6.exceptionspread.InBoundHandlerC;
import com.netty.flash.ex6.exceptionspread.OutBoundHandlerA;
import com.netty.flash.ex6.exceptionspread.OutBoundHandlerB;
import com.netty.flash.ex6.exceptionspread.OutBoundHandlerC;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.AttributeKey;

/**
 * Pipeline:
 * 1.Pipeline的初始化
 * (1)Pipeline在创建Channel的时候被创建->newChannelPipeline()
 * 构造AbstratChannel通过 newChannelPipeline()创建Channel对应的Pipeline,创建TailContext tail节点和HeadContext head节点通过prev/next组成双向链表数据结构
 * (2)Pipeline节点数据结构:ChannelHandlerContext
 * ChannelHandlerContext继承AttributeMap[存储自定义属性]/ChannelInboundInvoker[inBound事件传播譬如Read/Register/Active事件]/ChannelOutboundInvoker[outBound事件传播譬如Write事件]
 * AbstractChannelHandlerContext实现ChannelHandlerContext通过next/prev属性串行结构
 * (3)Pipeline中的两大哨兵:head和tail
 * TailContext tail节点继承AbstractChannelHandlerContext即为Pipeline节点数据结构ChannelHandlerContext,实现ChannelInboundHandler传播inBound事件即属于Inbound处理器ChannelHandler,exceptionCaught()/channelRead()方法用于异常未处理警告/Msg未处理建议处理收尾
 * HeadContext head节点继承AbstractChannelHandlerContext即为Pipeline节点数据结构ChannelHandlerContext,实现ChannelOutboundHandler传播outBound事件即属于Outbound处理器ChannelHandler,使用pipeline.channel().unsafe()获取Channel的Unsafe实现底层数据读写,用于传播事件/读写事件委托Unsafe操作
 * <p>
 * 2.添加删除ChannelHandler
 * 添加ChannelHandler[封装ChannelHandler成ChannelHandlerContext节点通过链表的方式插入Pipeline回调添加完成事件]->addLast()
 * (1)判断是否重复添加->checkMultiplicity()
 * 通过checkMultiplicity()方法判断ChannelHandler是否为ChannelHandlerAdapter实例,ChannelHandler强制转换ChannelHandlerAdapter判断是否可共享[isSharable()]&是否已经被添加过[h.added],ChannelHandlerAdapter非共享并且已经被添加过抛出异常拒绝添加
 * (2)创建节点并添加至链表[封装ChannelHandler成ChannelHandlerContext添加到链表]->newContext()/addLast0()
 * 通过filterName()检查重复ChannelHandler名称调用newContext()封装ChannelHandler构造DefaultChannelHandlerContext创建节点ChannelHandlerContext
 * 调用addLast0()方法获取tail节点的前置节点prev,将当前节点的前置节点prev置为tail节点的前置节点prev,当前节点的后置节点next置为tail节点,tail节点的前置节点prev的后置节点next置为当前节点,tail节点的前置节点prev置为当前节点,通过链表的方式添加到Channel的Pipeline
 * (3)回调添加完成事件->callHandlerAdded0()
 * 调用callHandlerAdded0()方法添加ChannelInitializer执行handlerAdded()方法回调自定义的添加完成事件删除自身节点
 * 删除ChannelHandler[权限校验场景]->remove()
 * (1)找到节点->getContextOrDie()
 * 通过getContextOrDie()方法根据ChannelHandler使用context()方法从head节点的后置节点next遍历循环判断节点的Handler是否为指定ChannelHandler获取封装ChannelHandler的ChannelHandlerContext节点,ChannelHandlerContext节点为空抛异常
 * (2)链表的删除->remove()
 * 调用remove()方法删除封装ChannelHandler的ChannelHandlerContext节点,使用remove0()方法获取当前节点的前置节点prev和后置节点next,前置节点prev的后置节点next置为当前节点的后置节点next,后置节点next的后置节点prev置为当前节点的前置节点prev,
 * (3)回调删除Handler事件->callHandlerRemoved0()
 * 调用callHandlerRemoved0()方法获取当前节点的ChannelHandler使用handlerRemove()方法回调删除Handler事件
 * <p>
 * 3.事件和异常的传播:
 * inBound事件的传播
 * (1)何为inBound事件以及ChannelInboundHandler
 * inBound事件[事件触发]包括Registered事件[Channel已注册/未注册NioEventLoop对应的Selector]、Active事件[Channel激活/失效]、Read事件[Channel读取数据/接收连接]、Channel读取完毕事件、自定义事件激活、可写状态改变事件以及异常捕获回调
 * ChannelInboundHandler继承ChannelHandler添加channelRegistered()/channelActive()/channelRead()/userEventTriggered()等事件,添加到Pipeline通过instanceOf关键词判断当前ChannelHandler类型设置inbound为true标识为ChannelInboundHandler处理inBound事件
 * (2)ChannelRead事件的传播
 * Head=====A=====C=====B=====Tail
 *     |(Pipeline) |(当前节点)
 * channelRead()
 * 通过fireChannelRead()方法按照添加ChannelRead事件顺序进行正序[findContextInbound()即添加ChannelInboundHandler顺序正序]传播,从head节点事件传播[ChannelPipeline#fireChannelRead()]/从当前节点事件传播[ChannelHandlerContext#fireChannelRead()],最终传播到tail节点调用onUnhandledInboundMessage()方法使用ReferenceCountUtil的release()方法释放ByteBuf对象
 * (3)SimpleInboundHandler处理器
 * ChannelRead事件传播覆盖channelRead()方法当前对象为ByteBuf并且读写处理ByteBuf没有往下传播则传播不到tail节点无法自动释放ByteBuf导致内存泄漏,继承SimpleInboundHandler自定义覆盖channelRead0()方法,SimpleInboundHandler的channelRead()方法调用自定义channelRead0()方法最终调用ReferenceCountUtil的release()方法自动释放ByteBuf对象
 * <p>
 * outBound事件的传播
 * (1)何为outBound事件以及ChannelOutboundHandler
 * outBound事件[发起事件]包括Bind事件[端口绑定]、Connect事件[连接/断连]、Close事件[关闭]、取消注册事件、Read事件、Write事件以及Flush事件回调
 * ChannelOutboundHandler添加到Pipeline通过instanceOf关键词判断当前ChannelHandler类型设置outbound为true标识为ChannelOutboundHandler处理outBound事件
 * (2)write()事件的传播
 * Head=====A=====C=====B=====Tail
 *                                    (当前节点)| (Pipeline)|
 *                                                                    write()
 * 通过write()方法按照添加Write事件顺序进行倒序[findContextOutbound()即添加ChannelOutboundHandler顺序倒序]传播,从tail节点事件传播[ChannelPipeline#write()]/从当前节点事件传播[ChannelHandlerContext#write()],最终传播到head节点调用Unsafe的write()方法
 * <p>
 * 异常的传播
 * (1)异常的触发链
 * Head=====IA=====IB=====IC=====OA=====OB=====OC=====Tail
 *                                        |
 *                                 Exception
 * 调用channelRead()方法抛异常使用notifyHandlerException()方法发起Exception事件,通过fireExceptionCaught()方法按照添加Exception事件顺序进行正序[添加ChannelHandler顺序正序]从当前节点触发传播到后置节点next,最终传播到tail节点调用onUnhandledInboundException()方法打印Pipeline未处理异常日志告警
 * (2)异常处理的最佳实践
 * Pipeline添加ChannelHandler最后添加ExceptionCaughtHandler异常捕获处理器,所有异常都归异常捕获处理器按照异常类型分别处理
 * <p>
 * Pipeline在创建服务端/客户端Channel通过newChannelPipeline()创建,Pipeline数据结构是双向链表,每个节点都是封装ChannelHandler的ChannelHandlerContext,添加删除ChannelHandler在Pipeline链表结构添加删除ChannelHandlerContext节点,添加ChannelHandler通过instanceOf关键词判断ChannelHandler类型,ChannelHandler实现ChannelInboundHandler/ChannelOutboundHandler设置inbound/outbound为true标识Handler处理inbound/outBound事件,Pipeline默认结构存在两种类型节点:head节点[Unsafe负责Channel具体协议]和tail节点[终止事件和异常传播],
 * Pipeline传播机制分为三种传播:inBound事件的传播[默认情况调用Channel触发事件,触发规则是不断寻找下一个ChannelInboundHandler最终传播到tail节点,当前ChannelHandlerContext触发inBound事件从当前节点向下传播],outBound事件的传播[默认情况调用Channel触发事件,触发规则是不断寻找上一个ChannelOutboundHandler最终head节点的Unsafe负责真正的写操作,当前ChannelHandlerContext触发outBound事件从当前节点向上传播],异常事件的传播[ChannelHandlerContext节点读写数据出错抛异常从当前节点往下异常传播,最终传播到tail节点打印异常信息],异常处理最佳实践是Pipeline最后添加异常捕获处理器对不同异常类型分别处理
 * <p>
 * Netty是如何判断ChannelHandler类型的?Pipeline添加ChannelHandler调用newContext()创建ChannelHandlerContext节点使用isInbound()/isOutbound()方法通过instanceOf关键词判断ChannelHandler类型为ChannelInboundHandler或者ChannelOutboundHandler,设置inbound/outbound为true标识Handler处理inbound/outBound事件
 * 对于ChannelHandler的添加应该遵循什么样的顺序?inBound事件的传播跟添加ChannelHandler顺序正相关,outBound事件的传播跟添加ChannelHandler顺序逆相关
 * 用户手动触发事件传播,不同的触发方式有什么样的区别?通过Channel触发事件从head节点传播即为inBound事件传播,从tail节点传播即为outBound事件传播,当前节点触发事件从当前节点开始传播,inBound事件从当前节点向后传播到最后一个ChannelInboundHandler节点,outBound事件从当前节点向前传播到第一个ChannelOutboundHandler节点
 */
public final class Server {

    public static void main(String[] args) throws Exception {
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();

        try {
            ServerBootstrap serverBootstrap = new ServerBootstrap();
            serverBootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childAttr(AttributeKey.newInstance("childAttr"), "childAttrValue")
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        public void initChannel(SocketChannel ch) {
                            //-----------------------------Pipeline的初始化-----------------------------
                            //ch.pipeline().addLast(new ChannelInboundHandlerAdapter());
                            //ch.pipeline().addLast(new ChannelOutboundHandlerAdapter());
                            //-----------添加删除ChannelHandler-----------
                            // ch.pipeline().addLast(new AuthHandler());
                            //-------------------inBound事件的传播-----------------
                            //ch.pipeline().addLast(new InBoundHandlerA());
                            //ch.pipeline().addLast(new InBoundHandlerB());
                            //ch.pipeline().addLast(new InBoundHandlerC());
                            //---------------------------------------------------------------
                            //ch.pipeline().addLast(new InBoundHandlerA());
                            //ch.pipeline().addLast(new InBoundHandlerC());
                            //ch.pipeline().addLast(new InBoundHandlerB());
                            //------------------outBound事件的传播------------------
                            //ch.pipeline().addLast(new OutBoundHandlerA());
                            //ch.pipeline().addLast(new OutBoundHandlerB());
                            //ch.pipeline().addLast(new OutBoundHandlerC());
                            //----------------------------------------------------------------
                            //ch.pipeline().addLast(new OutBoundHandlerA());
                            //ch.pipeline().addLast(new OutBoundHandlerC());
                            //ch.pipeline().addLast(new OutBoundHandlerB());
                            //---------------------异常的传播--------------------------
                            ch.pipeline().addLast(new InBoundHandlerA());
                            ch.pipeline().addLast(new InBoundHandlerB());
                            ch.pipeline().addLast(new InBoundHandlerC());
                            ch.pipeline().addLast(new OutBoundHandlerA());
                            ch.pipeline().addLast(new OutBoundHandlerB());
                            ch.pipeline().addLast(new OutBoundHandlerC());
                            ch.pipeline().addLast(new ExceptionCaughtHandler());
                        }
                    });

            ChannelFuture channelFuture = serverBootstrap.bind(8888).sync();

            channelFuture.channel().closeFuture().sync();
        } finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }
}