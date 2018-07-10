package com.netty.flash.ex2;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * 服务器
 */
public class Server {
    private ServerSocket serverSocket;

    public Server(int port) {
        try {
            //创建服务端ServerSocket绑定port
            this.serverSocket = new ServerSocket(port);
            System.out.println("服务端启动成功,端口:" + port);
        } catch (IOException e) {
            e.printStackTrace();
            System.out.println("服务端启动失败");
        }
    }

    public void start() {
        //创建端口监听线程避免阻塞ServerBoot线程
        new Thread(new Runnable() {
            @Override
            public void run() {
                doStart();
            }
        }).start();
    }

    /**
     * 接收客户端连接
     */
    private void doStart() {
        //NioEventLoop的run()方法while(true)
        while (true) {
            try {
                //NioEventLoop的select操作
                Socket client = serverSocket.accept();
                //NioEventLoop的processSelectedKeys操作
                new ClientHandler(client).start();
            } catch (IOException e) {
                e.printStackTrace();
                System.out.println("服务端异常");
            }
        }
    }
}