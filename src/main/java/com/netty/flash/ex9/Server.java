package com.netty.flash.ex9;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;

/**
 * Netty编码
 *                        writeAndFlush()
 * ----------     -------------     ------     ------     --------
 * | Head | = | encoder | = | ... | = | biz | = | Tail |
 * ----------     -------------     ------     ------     --------
 *                         |                            |
 *       writeAndFlush(bytebuf) writeAndFlush(user)
 * writeAndFlush()抽象步骤
 * 1.从tail节点开始往前传播->pipeline.writeAndFlush()
 * 调用AbstractChannel的writeAndFlush()方法使用pipeline的writeAndFlush()方法,通过尾节点Tail的writeAndFlush()方法从tail节点开始往前传播,调用AbstractChannelHandlerContext的write()方法判断当前线程是否为Reactor线程,是Reactor线程按照参数flush值调用invokeWriteAndFlush()/invokeWrite()方法,非Reactor线程封装成Task扔到Nio线程的TaskQueue里面执行
 * 2.逐个调用channelHandler的write方法->invokeWrite0()
 * invokeWrite0()方法在Pipeline里面的每个ChannelHandler向前传播逐个调用ChannelHandler的write()方法,调用MessageToByteEncoder的write()方法使用encode()抽象方法进行编码
 * 3.逐个调用channelHandler的flush方法->invokeFlush0()
 * invokeFlush0()方法在Pipeline里面的每个ChannelHandler向前传播逐个调用ChannelHandler的flush()方法,默认情况从tail节点往前传播ChannelHandler不覆盖write()/flush()方法传播到head节点
 * <p>
 * 编码器处理逻辑:MessageToByteEncoder覆盖write()方法
 * 匹配对象->分配内存->编码实现->释放对象->传播数据->释放内存
 * 1.匹配对象[判断当前是否能够处理对象,不能处理向前扔到前面ChannelHandler处理]->acceptOutboundMessage()
 * 调用acceptOutboundMessage()方法通过matcher的match()方法判断当前对象是否匹配即msg是否为type的实例
 * 2.分配内存[ByteBuf开辟内存空间,对象转换为字节存在分配内存里面]->allocateBuffer()
 * 调用allocateBuffer()方法通过ChannelHandlerContext的alloc()方法分配堆内/堆外内存heapBuffer()/ioBuffer()
 * 3.编码实现[覆盖MessageToByteEncoder的encode()方法,encode()方法实现自定义协议]->encode()
 * 调用encode()方法传参转换后的对象和分配ByteBuf把转换后的对象填充到分配ByteBuf,留给子类自定义实现
 * 4.释放对象[MessageToByteEncoder和转换前的ByteBuf对象需要释放,不需要encode()方法释放]->ReferenceCountUtil.release()
 * 调用ReferenceCountUtil的release()方法将ByteBuf类型的cast对象即ReferenceCounted实例释放
 * 5.传播数据[把编码过后的二进制数据包进行往前传播]->write()
 * 把填充好的ByteBuf往前一直扔到head节点,head节点负责把数据写到底层,buf可读表示调用encode()方法给buf写进数据调用ChannelHandlerContext的write()方法把写好的数据一直往前进行传播到head节点,buf不可读表示调用encode()方法未写进数据调用release()方法释放内存并且传播EMPTY_BUFFER空ByteBuf,buf赋值为空
 * 6.释放内存[出现异常需要释放内存避免内存泄漏]
 * write过程出现异常判断buf是否为空,非空调用release()方法将申请的内存释放
 * <p>
 * write-写buffer队列
 * 1.direct化ByteBuf[ByteBuf非堆外内存转换成堆外内存]->filterOutboundMessage()
 * 获取outboundBuffer对象负责缓冲写进来的ByteBuf,调用filterOutboundMessage()方法判断对象是否为ByteBuf实例并且buf是堆外内存直接返回/buf非堆外内存通过newDirectBuffer()方法创建DirectBuffer调用writeBytes()复制方式把原始buf里面字节写到DirectBuffer把buf封装成DirectBuffer
 * 2.插入写队列[ByteBuf封装成Entry插入写队列,通过一系列指针标识ByteBuf状态]->addMessage()
 * 调用outboundBuffer的addMessage()方法将DirectBuffer插入到outboundBuffer写缓存区,通过Entry的newInstance()方法把ByteBuf封装成Entry实例entry判断tailEntry是否为空,tailEntry为空flushedEntry赋值为空并且tailEntry为entry,tailEntry非空tailEntry的后置节点next追加entry并且tailEntry赋值为entry,判断unflushedEntry是否为空unflushedEntry赋值为entry
 * Entry(flushedEntry[链表结构第一个已被Flush过的Entry]) --> ... Entry(unflushedEntry[链表结构第一个未被Flush过的Entry]) --> ... Entry(tailEntry[buffer尾部tail Entry])
 * 第一次调用write
 *                                         ------------
 * NULL                              |             |
 *                                         ------------
 *     |                                         /\
 * flushedEntry  unflushedEntry  tailEntry
 * 第二次调用write
 *                          ------------      ------------
 * NULL                |           |       |            |
 *                          ------------      ------------
 *     |                           |                   |
 * flushedEntry  unflushedEntry  tailEntry
 * 第N次调用write
 *                          ------------  ------------   ------------
 * NULL               |            |   |   N-2   |   |             |
 *                          ------------  ------------   ------------
 *     |                           |                                 |
 * flushedEntry  unflushedEntry                tailEntry
 * 3.设置写状态[内存不足不能一直往队列里面插入ByteBuf,ByteBuf超过一定容量抛异常]->incrementPendingOutboundBytes()
 * 调用incrementPendingOutboundBytes()方法统计当前有多少字节需要被写,按照当前缓冲区里面待写字节TOTAL_PENDING_SIZE_UPDATER累加ByteBuf长度size计算获取newWriteBufferSize判断newWriteBufferSize是否大于Channel的写Buffer高水位标志[DEFAULT_HIGH_WATER_MARK即64K]配置,超过Channel的写Buffer高水位标志调用setUnwritable()方法通过自旋锁和CAS操作使用fireChannelWritabilityChanged()方法传播事件,通过Pipeline传播到ChannelHandler设置不可写状态
 * <p>
 * flush-刷新buffer
 * 1.添加刷新标志并设置写状态[把缓冲区里面的数据写到Socket里面更新写状态为可写]->addFlush()
 * 调用addFlush()方法默认情况unflushedEntry不为空并且flushedEntry为空把entry指向unflushedEntry将flushedEntry指向第一个unflushedEntry表明flushedEntry是可写的,while循环flushed自增1表示当前可以flush多少个对象,调用decrementPendingOutboundBytes()方法按照当前缓冲区里面待写字节TOTAL_PENDING_SIZE_UPDATER减少ByteBuf长度size计算获取newWriteBufferSize判断newWriteBufferSize是否小于Channel的写Buffer低水位标志[DEFAULT_LOW_WATER_MARK即32K]配置,低于Channel的写Buffer低水位标志调用setWritable()方法通过自旋锁和CAS操作使用fireChannelWritabilityChanged()方法传播事件,通过Pipeline传播到ChannelHandler设置可写状态,unflushedEntry赋值为空表示当前Entry都是可写的
 *                                             flush
 *                          ------------  ------------   ------------    ------------
 * NULL               |            |   |            |   |     ...     |   |             |
 *                          ------------  ------------   ------------    ------------
 *     |                           |                                                   |
 * unflushedEntry  flushedEntry                                   tailEntry
 * 2.遍历buffer队列,过滤ByteBuf[往Socket通道写数据需要把缓冲区里面的ByteBuf过滤]->flush0()
 * 调用flush0()方法通过inFlush0标志已经在Flush避免重复进入,使用doWrite()方法循环通过current()方法获取当前节点msg即第一个flushedEntry的ByteBuf判断是否为ByteBuf实例进行过滤
 * 3.调用JDK底层API进行自旋写->doWriteBytes()
 * 将msg强制转换成ByteBuf实例buf判断ByteBuf里面是否有可读字节,无可读字节调用remove()方法将当前节点移除,获取写自旋锁[提高内存利用率和写入吞吐量]次数[默认值为16],循环使用doWriteBytes()方法通过javaChannel()获取JDK原生Channel调用PooledDirectByteBuf的readBytes()方法写到原生Channel,使用getBytes()方法把自定义的ByteBuf设置一系列读指针标记塞到tmpBuf调用JDK的write()方法从ByteBuf写到JDK底层的Socket获取写ByteBuf字节计算向JDK底层写字节localFlushedAmount,flushedAmount累加localFlushedAmount判断ByteBuf是否有可读字节,无可读字节表示ByteBuf全部写到JDK底层设置done为true,写完ByteBuf数据调用remove()方法获取当前flushedEntry使用removeEntry()方法把当前flushedEntry移除设置flushedEntry为当前Entry后置节点next
 * <p>
 * 如何把对象变成字节流,最终写到Socket底层?BizHandler把自定义对象通过writeAndFlush()方法往前传播拆分成两个过程:1.write()方法通过Pipeline逐个ChannelHandler往前传播,传播到Encoder节点继承MessageToByteEncoder负责覆盖write()方法将自定义对象转换成ByteBuf,MessageToByteEncoder分配ByteBuffer调用encode()抽象方法由子类实现把自定义对象填充到ByteBuf继续调用write()方法将ByteBuf往前传播,默认情况无覆盖write()方法最终传播到head节点,head节点通过底层Unsafe把当前ByteBuf塞到Unsafe底层维护的outboundBuffer缓冲区对象并且计算ByteBuf超过最高水位设置当前通道不可写,write操作完成之后head节点底层维护的缓冲区里面对应ByteBuf链表;2.flush()方法从tail节点通过Pipeline逐个ChannelHandler往前传播,默认情况无覆盖flush()方法最终传播到head节点,head节点调用底层Unsafe把指针进行一系列调整通过循环不断往缓冲区里面获取ByteBuf转换成JDK底层的ByteBuffer对象使用JDK的Channel把ByteBuffer写到Socket删除节点,缓冲区里面当前可写字节小于最低水位改变Channel状态,最高水位64K/最低水位32K
 */
public final class Server {

    public static void main(String[] args) throws Exception {
        EventLoopGroup bossGroup = new NioEventLoopGroup();
        EventLoopGroup workderGroup = new NioEventLoopGroup();

        try {
            ServerBootstrap serverBootstrap = new ServerBootstrap();
            serverBootstrap.group(bossGroup, workderGroup).channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) throws Exception {
                            ch.pipeline().addLast(new Encoder());
                            ch.pipeline().addLast(new BizHandler());
                        }
                    });
            ChannelFuture channelFuture = serverBootstrap.bind(8888).sync();

            channelFuture.channel().closeFuture().sync();
        } finally {
            bossGroup.shutdownGracefully();
            workderGroup.shutdownGracefully();
        }
    }
}