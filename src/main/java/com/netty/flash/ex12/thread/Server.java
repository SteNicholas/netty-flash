package com.netty.flash.ex12.thread;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.FixedLengthFrameDecoder;

import static com.netty.flash.ch12.thread.Constant.PORT;

/**
 * Netty高并发性能调优:
 * 2.Netty应用级别性能调优:
 * 1.ChannelHandler自定义业务线程池,将数据库或者网络阻塞连接线程耗时操作由主线程搬迁到业务线程池执行,其他的操作依然在Reactor线程中执行,根据QPS调整业务线程池线程数量,按照响应时间跟线程数量绘制曲线选择最优业务线程池线程数量
 * 2.添加ChannelHandler提供指定EventLoopGroup,将ChannelHandler里面方法所有的操作都放在此线程池处理,根据QPS调整NioEventLoopGroup线程数量,按照响应时间跟线程数量绘制曲线选择最优NioEventLoopGroup线程数量,弊处是ChannelHandler操作都是在单独的业务线程池处理导致分配内存无法进行线程间共享
 * 如果使用Netty自定义线程池,那么Handler里面的方法的所有的代码都会在这个线程池里面执行,而自定义线程池可以更加细粒度控制,只把耗时的操作放到线程池中执行,其他的操作依然可以在Reactor线程中执行
 * 因为在Netty里面每个线程都维护自己的一个内存空间,自定线程池可以做到分配内存依然在Netty的Reactor线程中执行,共享Reactor线程的内存空间,而如果使用Netty自定义线程池,由于整体代码都在这个自定义的线程池里面去执行,所以内存分配相关的操作都在这个线程池的内存空间去进行,无法共享到Reactor线程维护的内存空间,不过在这个自定义线程池内部来说,它的内存依然是可以共享的
 * <p>
 * 1.如果QPS过高,数据传输过快的情况下,调用writeAndFlush可以考虑拆分成多次write,然后单次flush,也就是批量flush操作
 * 2.分配和释放内存尽量在reactor线程内部做,这样内存就都可以在reactor线程内部管理
 * 3.尽量使用堆外内存,尽量减少内存的copy操作,使用CompositeByteBuf可以将多个ByteBuf组合到一起读写
 * 4.外部线程连续调用eventLoop的异步调用方法的时候,可以考虑把这些操作封装成一个task,提交到eventLoop,这样就不用多次跨线程
 * 5.尽量调用ChannelHandlerContext.writeXXX()方法而不是channel.writeXXX()方法,前者可以减少pipeline的遍历
 * 6.如果一个ChannelHandler无数据共享,那么可以搞成单例模式,标注@Shareable,节省对象开销对象
 * 7.如果要做网络代理类似的功能,尽量复用eventLoop,可以避免跨reactor线程
 */
public class Server {

    public static void main(String[] args) {
        EventLoopGroup bossGroup = new NioEventLoopGroup();
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        EventLoopGroup businessGroup = new NioEventLoopGroup(1000);

        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup);
        bootstrap.channel(NioServerSocketChannel.class);
        bootstrap.childOption(ChannelOption.SO_REUSEADDR, true);

        bootstrap.childHandler(new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel ch) throws Exception {
                //FixedLengthFrameDecoder拆解数据包
                ch.pipeline().addLast(new FixedLengthFrameDecoder(Long.BYTES));
                //ch.pipeline().addLast(ServerBusinessHandler.INSTANCE);
                //1.数据库或者网络阻塞连接耗时操作由主线程搬迁到业务线程池执行
                //ch.pipeline().addLast(ServerBusinessThreadPoolHandler.INSTANCE);
                //2.添加ChannelHandler提供指定EventLoopGroup单独的线程池处理
                ch.pipeline().addLast(businessGroup, ServerBusinessHandler.INSTANCE);
            }
        });

        bootstrap.bind(PORT).addListener((ChannelFutureListener) future ->
                System.out.println("Bind success in port: " + PORT));
    }
}