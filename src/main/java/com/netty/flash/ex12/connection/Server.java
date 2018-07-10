package com.netty.flash.ex12.connection;

import com.netty.flash.ch12.connection.ConnectionCountHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;

import static com.netty.flash.ex12.connection.Constant.BEGIN_PORT;
import static com.netty.flash.ex12.connection.Constant.N_PORT;

/**
 * Netty高并发性能调优:
 * 1.单机百万连接调优
 * (1)如何模拟百万连接
 * ----------------------------   ----------------------------
 * |      Server 8000       |   |  Server 8000~8100  |
 * ----------------------------   ----------------------------
 *                | 6w                                | 100*6w
 * ----------------------------    ----------------------------
 * | Client 1025~65535 |   | Client 1025~65535 |
 * ----------------------------    ----------------------------
 * 服务端只开启一个端口,客户端连接最多只能实现单机6w左右连接,一条TCP连接是由四元组(源ip,源端口,目的ip,目的端口)组成,其中有一个元素不一样连接就不一样,所以对于同一个服务端不同端口号,客户端的端口可以是相同的,如果服务端开100个端口，那么客户端可以开1w个端口与服务端100个端口笛卡尔积交叉连接这样就能达到百万
 * (2)突破局部文件句柄限制:Linux系统默认情况单个进程打开句柄数量是有限的,一条TCP连接对应一个句柄,一个服务端建立连接数量有限制
 * <1>ulimit -n:一个进程能够打开的最大文件数,一条TCP连接对应Linux系统里的一个文件,服务端最大连接数受限于此数字
 * <2>/etc/security/limits.conf
 * *(当前用户) hard(限制) nofile 1000000(一个进程能够打开的最大连接数)
 * *                   soft(警告)   nofile 1000000
 * (3)突破全局文件句柄限制
 * <1>cat /proc/sys/fs/file-max[及时修改,重启还原]:Linux系统所有进程能够打开的最大连接数
 * <2>/etc/sysctl.conf
 *  fs.file-max=1000000
 *  sysctl -p /etc/sysctl.conf
 */
public final class Server {

    public static void main(String[] args) {
        new Server().start(BEGIN_PORT, N_PORT);
    }

    public void start(int beginPort, int nPort) {
        System.out.println("Server starting....");

        EventLoopGroup bossGroup = new NioEventLoopGroup();
        EventLoopGroup workerGroup = new NioEventLoopGroup();

        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup);
        bootstrap.channel(NioServerSocketChannel.class);
        bootstrap.childOption(ChannelOption.SO_REUSEADDR, true);

        bootstrap.childHandler(new ConnectionCountHandler());

        for (int i = 0; i < nPort; i++) {
            int port = beginPort + i;
            bootstrap.bind(port).addListener((ChannelFutureListener) future -> {
                System.out.println("bind success in port: " + port);
            });
        }
        System.out.println("Server started!");
    }
}