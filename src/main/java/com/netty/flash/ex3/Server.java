package com.netty.flash.ex3;

import com.netty.flash.ch3.ServerHandler;
import com.netty.flash.ch6.AuthHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.AttributeKey;

/**
 * Netty服务端启动:
 * 1.创建服务端Channel:创建底层JDK Channel,封装JDK Channel,创建基本组件绑定Channel;
 * <p>
 * (1)bind(port)[用户代码入口]:serverBootstrap.bind(port)
 * (2)initAndRegister()[初始化并注册]
 * (3)newChannel()[创建服务端Channel]:
 * 通过serverBootstrap.channel()方法传入NioServerSocketChannel类构造ReflectiveChannelFactory实例将NioServerSocketChannel类设置为反射类;
 * channelFactory.newChannel()通过clazz.newInstance()调用反射类构造方法反射创建服务端Channel
 * <p>
 * 反射创建服务端Channel(查看NioServerSocketChannel构造方法):
 * (1)newSocket()[通过JDK来创建底层JDK Channel]:provider.openServerSocketChannel()
 * (2)AbstractNioChannel()
 * [1]AbstractChannel()[创建id,unsafe,pipeline]
 * [2]configureBlocking(false)[阻塞模式]:设置非阻塞模式
 * (3)NioServerSocketChannelConfig()[TCP参数配置类]:设置底层JDK Channel TCP参数配置例如backlog、receivebuf、sendbuf
 * <p>
 * 2.初始化服务端Channel:设置Channel基本属性,添加逻辑处理器;
 * <p>
 * (1)init()[初始化服务端channel,初始化入口]
 * (2)set ChannelOptions,ChannelAttrs
 * (3)set ChildOptions,ChildAttrs:提供给通过服务端Channel创建的新连接Channel创建的,每次accept新连接都配置用户自定义的两个属性配置
 * (4)config handler[配置服务端Pipeline]
 * (5)add ServerBootstrapAcceptor[添加连接器]:提供给accept接入的新连接分配NIO线程
 * 保存用户自定义基本属性,通过配置属性创建连接接入器,连接接入器每次accept新连接使用自定义属性配置新连接
 * <p>
 * 3.注册Selector:将底层JDK Channel注册到事件轮询器Selector上面,并把服务端Channel作为Attachment绑定在对应底层JDK Channel;
 * <p>
 * (1)AbstractChannel.register(channel)[注册Selector入口]
 * (2)this.eventLoop=eventLoop[绑定线程]
 * (3)register0()[实际注册]
 * [1]doRegister()[调用JDK底层注册]:JDK Channel注册Selector调用javaChannel().register(eventLoop().selector, 0, this),将服务端Channel通过Attachment绑定到Selector
 * [2]invokeHandlerAddedIfNeeded():事件回调,触发Handler
 * [3]fireChannelRegistered()[传播事件]
 * <p>
 * 4.端口绑定:实现本地端口监听
 * <p>
 * (1)AbstractUnsafe.bind()[端口绑定]
 * (2)doBind():javaChannel().bind()[JDK动态绑定]
 * (3)pipeline.fireChannelActive()[传播事件]:HeadContext.readIfIsAutoRead()将注册Selector的事件重新绑定为OP_ACCEPT事件,有新连接接入Selector轮询到OP_ACCEPT事件最终将连接交给Netty处理
 * 绑定OP_ACCEPT事件:当端口完成绑定触发active事件,active事件最终调用channel的read事件,read对于服务器来说可以读新连接
 * <p>
 * 服务端Socket在哪里初始化?反射创建服务端Channel:NioServerSocketChannel默认构造方法调用newSocket()使用provider.openServerSocketChannel()创建服务端Socket
 * 在哪里accept连接?端口绑定:Pipeline调用fireChannelActive()传播active事件,HeadContext使用readIfIsAutoRead()重新绑定OP_ACCEPT事件,新连接接入Selector轮询到OP_ACCEPT事件处理
 * <p>
 * 服务端启动核心路径总结:
 * 首先调用newChannel()创建服务端Channel,调用JDK底层API创建JDK Channel,Netty包装JDK底层Channel,创建基本组件绑定Channel例如Pipeline;
 * 然后调用init()方法初始化服务端Channel,为服务端Channel添加连接处理器;
 * 随后调用register()方法注册Selector,将JDK Channel注册到事件轮询器Selector上面,并将服务端Channel作为Attachment绑定到对应JDK底层Channel;
 * 最后调用doBind()方法实现对本地端口监听,绑定成功重新向Selector注册OP_ACCEPT事件接收新连接
 */

/**
 * NioEventLoop:
 * 1.NioEventLoop创建
 * (1)new NioEventLoopGroup()[线程组,默认2*cpu即Runtime.getRuntime().availableProcessors()*2]
 * [1]new ThreadPerTaskExecutor()[线程创建器]:创建线程执行器,线程执行器的作用是负责创建NioEventLoopGroup对应底层线程
 * ThreadPerTaskExecutor:通过构造ThreadFactory,每次执行任务创建线程然后运行线程
 * 每次执行任务都会创建一个线程实体FastThreadLocalThread;
 * NioEventLoop线程命名规则nioEventLoop-1[线程池,第几个NioEventLoopGroup即poolId.incrementAndGet()]-xx[NioEventLoopGroup第几个NioEventLoop即nextId.incrementAndGet()]
 * [2]for(){newChild()}[构造NioEventLoop]:创建NioEventLoop对象数组,for循环创建每个NioEventLoop,调用newChild()配置NioEventLoop核心参数
 * newChild():创建NioEventLoop线程
 * 保持线程执行器ThreadPerTaskExecutor;
 * 创建一个MpscQueue:taskQueue用于外部线程执行Netty任务的时候,如果判断不是在NioEventLoop对应线程里面执行,而直接塞到任务队列里面,由NioEventLoop对应线程执行,PlatformDependent.newMpscQueue(maxPendingTasks)创建MpscQueue保存异步任务队列;
 * 创建一个selector:provider.openSelector()创建selector轮询初始化连接
 * [3]chooserFactory.newChooser()[线程选择器]:创建线程选择器,给每个新连接分配NioEventLoop线程
 * isPowerOfTwo()[判断是否是2的幂,如2,4,8,16]:判断当前创建的NioEventLoopGroup数组个数是否是2的幂
 * PowerOfTowEventExecutorChooser[优化]:NioEventLoop索引下标=index++&(length-1)即idx.getAndIncrement() & executors.length - 1
 * idx                               111010
 * &
 * executors.length - 1         1111
 * result                              1010
 * GenericEventExecutorChooser[普通]:NioEventLoop索引下标=abs(index++%length)即Math.abs(idx.getAndIncrement() % executors.length)
 * 调用chooser.next()方法给新连接绑定对应的NioEventLoop
 * <p>
 * 2.NioEventLoop启动
 * NioEventLoop启动触发器:
 * (1)服务端启动绑定端口
 * (2)新连接接入通过chooser绑定一个NioEventLoop
 * NioEventLoop启动流程:
 * (1)bind->execute(task)[入口]:调用bind()方法把具体绑定端口操作封装成Task,通过eventLoop()方法获取channelRegistered()注册绑定NioEventLoop执行NioEventLoop的execute()方法
 * (2)startThread()->doStartThread()[创建线程]:调用inEventLoop()方法判断当前调用execute()方法线程是否为NioEventLoop线程,通过startThread()方法创建启动线程
 * (3)ThreadPerTaskExecutor.execute():通过线程执行器ThreadPerTaskExecutor执行任务创建并启动FastThreadLocalThread线程
 * (4)thread = Thread.currentThread():NioEventLoop保存当前创建FastThreadLocalThread线程,保存的目的是为了判断后续对NioEventLoop相关执行的线程是否为本身,如果不是则封装成Task扔到TaskQueue串行执行实现线程安全
 * (5)NioEventLoop.run()[启动]
 * <p>
 * 3.NioEventLoop执行逻辑
 * NioEventLoop.run()->SingleThreadEventExecutor.this.run():
 * (1)run()->for(;;)
 * [1]select()[检查是否有io事件]:轮询注册到selector上面的io事件
 * <1>deadline以及任务穿插逻辑处理:计算本次执行select截止时间(根据NioEventLoop当时是否有定时任务处理)以及判断在select的时候是否有任务要处理
 * wakeUp标识当前select操作是否为唤醒状态,每次select操作把wakeUp设置为false标识此次需要进行select操作并且是未唤醒状态;
 * 获取当前时间,当前时间+定时任务队列第一个任务截止时间获取当前执行select操作不能超过的截止时间;
 * 计算当前是否超时,如果超时并且一次都未select则进行非阻塞select方法;
 * 如果未到截止时间继续判断异步任务队列是否有任务并且通过CAS操作将wakeUp设置为true调用非阻塞select方法
 * <2>阻塞式select:未到截止时间或者任务队列为空进行一次阻塞式select操作,默认时间为1s
 * 截止时间未到并且当前任务队列为空进行阻塞式select操作;
 * 每次select操作之后selectCnt++表示当前已经执行selectCnt次;
 * 如果已经轮询到事件或者当前select操作是否需要唤醒或者执行select操作已经被外部线程唤醒或者异步队列有任务或者当前定时任务队列有任务,满足一个条件则本次select操作终止
 * <3>避免JDK空轮询的Bug
 * 判断这次select操作是否阻塞timeoutMillis时间,未阻塞timeoutMillis时间表示可能触发JDK空轮询;
 * 判断触发JDK空轮询的次数是否超过阈值(默认512),达到阈值调用rebuildSelector()方法替换原来的selector操作方式避免下次JDK空轮询继续发生;
 * rebuildSelector()主要逻辑是把老的selector上面的所有selector key注册到新的selector上面,新的selector上面的select操作可能不会发生空轮询的Bug;
 * 通过openSelector()重新创建selector,获取旧的selector所有key和attachment,获取key的注册事件,将key的注册事件取消,注册到重新创建新的selector上面并且注册对应事件以及Netty封装的channel,如果attachment经Netty封装的NioChannel,将NioChannel的selectionKey赋值为新的selectionKey
 * 重新进行非阻塞式select
 * <p>
 * [2]processSelectedKeys()[处理io事件]
 * <1>selected keySet优化:select操作每次把已就绪状态的io事件添加到底层HashSet(时间复杂度为O(n))数据结构,通过反射方式将HashSet替换成数组的实现,任何情况下select操作时间复杂度为O(1)优于HashSet
 * 创建NioEventLoop调用openSelector()创建io事件轮询器Selector:调用provider.openSelector()创建Selector,通过1024大小数组构造SelectedSelectionKeySet,SelectedSelectionKeySet的add()方法使用doubleCapacity()方法扩容;
 * 通过反射方式获取SelectorImpl类对象并且判断是否为创建的Selector的实现,获取SelectorImpl类对象selectedKeys和publicSelectedKeys属性,设置NioEventLoop里面Selector的selectedKeys和publicSelectedKeys属性值为SelectedSelectionKeySet,每次select操作结束之后如果有io事件都把对应的Key塞到SelectedSelectionKeySet;
 * 将SelectedSelectionKeySet保存到NioEventLoop的selectedKeys成员变量
 * 用数组替换Selector HashSet实现,做到add()方法时间复杂度为O(1)
 * <2>processSelectedKeysOptimized()
 * 调用SelectedKeys的flip()方法获取SelectionKey数组,for循环遍历SelectionKey数组,设置SelectionKey引用为null,获取SelectionKey的attachment即NioChannel;
 * SelectionKey不合法即连接有问题Channel的Unsafe调用close()方法关闭连接,SelectionKey合法获取SelectionKey的io事件,如果当前NioEventLoop是Worker Group则io事件为OP_READ事件连接上面数据读写,如当前NioEventLoop是Boss Group则io事件为OP_ACCEPT事件有新连接接入
 * Netty默认情况通过反射将Selector底层HashSet转换成数组方式进行优化,处理每个SelectedSelectionKeySet获取对应的attachment即向Select注册io事件绑定的封装Channel
 * <p>
 * [3]runAllTasks()[处理异步任务队列]:处理外部线程扔到TaskQueue里面的任务
 * <1>Task的分类和添加:分为普通任务Task和定时任务Task,分别存放在普通任务队列MpscQueue和定时任务队列ScheduledTaskQueue
 * 普通任务队列MpscQueue在创建NioEventLoop构造的,外部线程调用NioEventLoop的execute()方法使用addTask()方法向TaskQueue添加task;
 * 定时任务队列ScheduledTaskQueue在调用NioEventLoop的schedule()方法将Callable任务封装成ScheduledFutureTask,判断是否为当前NioEventLoop发起的schedule还是外部线程发起的schedule,当前NioEventLoop发起的schedule直接添加定时任务,外部线程发起的schedule为了保证线程安全(ScheduledTaskQueue是PriorityQueue非线程安全)添加定时任务操作当做普通任务Task保证对于定时任务队列操作都在NioEventLoop实现
 * <2>任务的聚合->fetchFromScheduledTaskQueue():将定时任务队列ScheduledTaskQueue任务聚合到普通任务队列MpscQueue
 * while循环获取定时任务队列(按照截止时间&添加时间排序)截止时间为nanoTime的定时任务(截止时间最小)添加到普通任务队列,如果添加失败则重新将定时任务添加到定时任务队列里面,定时任务队列的所有任务都聚合到普通任务队列
 * <p>
 * <3>任务的执行
 * 调用pollTask()方法获取普通任务队列MpscQueue待执行任务计算截止时间;
 * 循环使用safeExecute()方法执行每个任务,执行完成的任务数量+1,累计执行完成的任务数量达到64计算当前时间判断是否超过截止时间,如果超过最大允许时间中断执行后续任务;
 * 继续获取普通任务队列MpscQueue待执行任务直到获取待执行任务结束,记录上一次执行时间并且使用afterRunningAllTasks()方法进行收尾
 * <p>
 * 用户代码创建Boss/Worker Group NioEventLoop创建,默认不传参创建2倍cpu核数个NioEventLoop,每个NioEventLoop都有线程选择器chooser进行线程分配并且优化NioEventLoop个数,构造NioEventLoop创建Selector和定时任务队列,创建Selector通过反射方式使用数组实现替换Selector HashSet数据结构;
 * NioEventLoop首次调用execute()方法启动FastThreadLocalThread线程,将创建完成的线程保存到成员变量判断执行NioEventLoop逻辑是否为本线程;NioEventLoop执行逻辑在run()方法主要包括三个过程:1.检测io事件,2.处理io事件,3.执行任务队列
 * <p>
 * 默认情况下,Netty服务端起多少线程?何时启动?
 * 默认2*cpu即Runtime.getRuntime().availableProcessors()*2]线程,调用execute()方法判断当前是否在本线程,如果是在本线程说明线程已经启动,如果是在外部线程调用execute()方法,首先调用startThread()方法判断当前线程是否启动,未启动就启动此线程
 * Netty是如何解决JDK空轮询Bug?
 * 判断阻塞select操作是否阻塞timeoutMillis时间,未阻塞timeoutMillis时间表示可能触发JDK空轮询;判断触发JDK空轮询的次数是否超过阈值(默认512),超过阈值调用rebuildSelector()方法重建Selector把之前的Selector上面所有的Key重新移到新的Selector避免JDK空轮询的Bug
 * Netty如何保证异步串行无锁化?
 * 外部线程调用EventLoop或者Channel方法通过inEventLoop()方法判断得出是外部线程,所有操作封装成Task丢到普通任务队列MpscQueue,异步执行普通任务队列MpscQueue待执行任务
 */

/**
 * Netty新连接接入:
 * Netty新连接接入处理逻辑:
 * 1.检测新连接:新连接通过服务端Channel绑定的Selector轮询OP_ACCEPT事件
 * (1)processSelectedKey(key,channel)[入口]
 * (2)NioMessageUnsafe.read():确保在该NioEventLoop方法里调用,获取服务端Channel的Config和Pipeline,分配器allocHandle处理服务端接入数据并且重置服务端Channel的Config,执行读取接收Channel,分配器allocHandle把读到的连接进行计数,分配器allocHandle调用continueReading()方法判断当前读到的总连接数是否超过每次最大读的连接数(默认为16)
 * (3)doReadMessages()[while循环]:调用javaChannel()方法获取服务端启动创建的JDK Channel,使用accept()方法获取JDK底层SocketChannel,SocketChannel封装成NioSocketChannel放到服务端Channel的MessageUnsafe临时承载读到的连接readBuf
 * (4)javaChannel().accept()
 * <p>
 * 2.创建NioSocketChannel:基于JDK的Nio Channel创建NioSocketChannel即客户端Channel
 * (1)new NioSocketChannel(parent, ch)[入口]
 * (2)AbstractNioByteChannel(p,ch, op_read):逐层调用父类构造函数设置Channel阻塞模式为false保存对应read读事件,创建id,unsafe以及pipeline组件
 * <1>configureBlocking(false)&save op
 * <2>create id(channel唯一标识),unsafe(底层数据读写),pipeline(业务数据逻辑载体)
 * (3)new NioSocketChannelConfig():创建NioSocketChannelConfig,默认设置TCP_NODELAY为true即小的数据包尽量发出去降低延迟
 * <1>setTcpNoDelay(true)禁止Nagle算法即小的数据包尽量发出去降低延迟
 * <p>
 * Netty中的Channel的分类:
 * NioServerSocketChannel[服务端Channel]:继承AbstractNioMessageChannel,注册AbstractNioChannel事件为OP_ACCEPT事件,创建NioMessageUnsafe&NioServerSocketChannelConfig
 * NioSocketChannel[客户端Channel]:继承AbstractNioByteChannel,注册AbstractNioChannel事件为OP_READ事件,创建NioByteUnsafe&NioSocketChannelConfig
 * Unsafe[实现Channel读写抽象]:服务端Channel对应NioMessageUnsafe[读连接],客户端Channel对应NioByteUnsafe[读数据]
 * Channel[网络Socket读、写、连接以及绑定最顶层抽象]
 * -->AbstractChannel[Channel抽象骨架实现,id/unsafe/pipeline/eventLoop组件抽象]
 * -->AbstractNioChannel[Nio通过使用Selector方式进行io读写事件监听,服务端/客户端Channel注册到Selector上面的selectionKey/服务端/客户端底层JDK Channel SeleteableChannel的ch/读OP_ACCEPT/OP_READ事件的readInterestOp,设置阻塞模式为false]
 * <p>
 * 3.分配线程及注册Selector:提供客户端Channel分配NioEventLoop,并且把Channel注册到NioEventLoop对应的Selector,Channel读写由NioEventLoop管理
 * 新连接NioEventLoop分配和selector注册:
 * 服务端Channel的pipeline构成:Head->ServerBootstrapAcceptor->Tail[传播Channel OP_READ事件:pipeline.fireChannelRead()即调用channelRead()]
 * ServerBootstrapAcceptor channelRead():
 * (1)添加childHandler[将用户自定义ChannelHandler添加到新连接的Pipeline里面]:child.pipeline().addLast(childHandler)->ChannelInitializer.handlerAdded()->ChannelInitializer.initChannel()
 * (2)设置options和attrs:设置childOptions[跟底层TCP读写相关,源自通过childOption()方法设置,ServerBootstrapAcceptor构造函数传递]和childAttrs[自定义属性比如密钥、存活时间等,源自通过childAttr()方法设置,ServerBootstrapAcceptor构造函数传递]
 * (3)选择NioEventLoop并注册到Selector:EventLoopGroup调用Chooser的next()方法选择获取NioEventLoop绑定到客户端Channel,通过doRegister()方法将新连接注册到NioEventLoop的Selector上面
 * <p>
 * 4.向Selector注册读事件
 * NioSocketChannel读事件的注册:
 * 调用Pipeline的fireChannelActive()使用channelActive()方法上下文传播active事件,通过readIfIsAutoRead()方法默认情况只要绑定端口自动读取连接即向Selector注册OP_READ事件
 * <p>
 * 服务端Channel绑定的NioEventLoop即Boss线程轮询OP_ACCEPT事件,调用服务端Channel的accept()方法获取客户端Channel封装成NioSocketChannel,封装创建NioSocketChannel组件Unsafe用来实现Channel读写和Pipeline负责数据处理业务逻辑链,
 * 服务端Channel通过连接接入器ServerBootstrapAcceptor给当前客户端Channel分配NioEventLoop,并且将客户端Channel绑定到唯一的Selector上面,通过传播Channel Active方法将客户端Channel读事件注册到Selector上面
 * <p>
 * Netty是在哪里检测有新连接接入的?Boss线程通过服务端Channel绑定的Selector轮询OP_ACCEPT事件,通过JDK底层Channel的accept()方法获取JDK底层SocketChannel创建新连接
 * 新连接是怎样注册到NioEventLoop线程的?Worker线程调用Chooser的next()方法选择获取NioEventLoop绑定到客户端Channel,使用doRegister()方法将新连接注册到NioEventLoop的Selector上面
 */
public class Server {

    public static void main(String[] args) throws InterruptedException {
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();

        try {
            ServerBootstrap serverBootstrap = new ServerBootstrap();
            serverBootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childAttr(AttributeKey.newInstance("childAttr"), "childAttrValue")
                    .handler(new ServerHandler())
                    .childHandler(new ChannelInitializer<SocketChannel>() {

                        @Override
                        protected void initChannel(SocketChannel ch) throws Exception {
                            ch.pipeline().addLast(new AuthHandler());
                            //..
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