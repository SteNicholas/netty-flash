package com.netty.flash.bo1;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * 开篇:Netty是什么?
 * 传统的IO模型每个连接创建成功都需要一个线程来维护,每个线程包含一个while死循环,那么1w个连接对应1w个线程,继而1w个while死循环带来如下几个问题：
 * 线程资源受限:
 * [IO编程]线程是操作系统中非常宝贵的资源,同一时刻有大量的线程处于阻塞状态是非常严重的资源浪费,操作系统耗不起.一个连接来了,会创建一个线程,对应一个while死循环,死循环的目的就是不断监测这条连接上是否有数据可以读,大多数情况下,1w个连接里面同一时刻只有少量的连接有数据可读,因此很多个while死循环都白白浪费掉,因为读不出啥数据
 * [NIO编程]NIO编程模型新来一个连接不再创建一个新的线程,把这条连接直接绑定到某个固定的线程,然后这条连接所有的读写都由该线程来负责.把这么多while死循环变成一个死循环,这个死循环由一个线程控制,一条连接来了,不创建一个while死循环去监听是否有数据可读,直接把这条连接注册到Selector上,然后通过检查Selector批量监测出有数据可读的连接进而读取数据
 * 线程切换效率低下:
 * [IO编程]单机CPU核数固定,线程爆炸之后操作系统频繁进行线程切换,应用性能急剧下降
 * [NIO模型]线程数量大大降低,线程切换效率因此也大幅度提高
 * 数据读写是以字节流为单位效率不高:
 * [IO编程]每次都是从操作系统底层一个字节一个字节地读取数据
 * [NIO编程]NIO维护一个缓冲区每次从这个缓冲区里面读取一块的数据,数据读写不再以字节为单位,而是以字节块为单位
 */
public class IOServer {

    /**
     * Server服务端首先创建ServerSocket监听8000端口,然后创建线程不断调用阻塞方法 serversocket.accept()获取新的连接,当获取到新的连接给每条连接创建新的线程负责从该连接中读取数据,然后读取数据是以字节流的方式
     *
     * @param args
     * @throws IOException
     */
    public static void main(String[] args) throws IOException {
        ServerSocket serverSocket = new ServerSocket(8000);

        //接收新连接线程
        new Thread(() -> {
            try {
                //(1)阻塞方法获取新的连接
                Socket socket = serverSocket.accept();

                //(2)每一个新的连接都创建一个线程,负责读取数据
                new Thread(() -> {
                    try {
                        byte[] data = new byte[1024];
                        InputStream inputStream = socket.getInputStream();
                        while (true) {
                            int len;
                            //(3)按照字节流方式读取数据
                            while ((len = inputStream.read(data)) != -1)
                                System.out.println(new String(data, 0, len));
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }).start();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }).start();
    }
}