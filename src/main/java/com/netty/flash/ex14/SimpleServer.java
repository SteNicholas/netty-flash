package com.netty.flash.ex14;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;

/**
 * nio线程个数以及线程命名规则[https://www.jianshu.com/p/512e983eedf5]
 * 线程个数在何时达到最优?cpu计算的时间为Tcpu,io操作的时间为Tio,系统的cpu核数为Ncpu,线程个数为Nthread, 理论上线程个数满足Nthread = (1+Tio/Tcpu)*Ncpu应用的性能达到最优
 * netty默认情况线程的个数为DEFAULT_EVENT_LOOP_THREADS[Math.max(1, SystemPropertyUtil.getInt("io.netty.eventLoopThreads", Runtime.getRuntime().availableProcessors() * 2))]即cpu的核数乘以2
 * 为什么netty要将worker的线程个数设置为2倍cpu个数?线程个数设置为2倍的cpu线程个数,Tio/Tcpu的值等于1即netty的nio线程cpu时间和io时间相等
 * netty默认nio线程由 DefaultThreadFactory的newThread(new DefaultRunnableDecorator(r), prefix+nextId.incrementAndGet())方法创建出来的
 * 线程命名规则为prefix[Character.toLowerCase(StringUtil.simpleClassName(getClass()).charAt(0))+StringUtil.simpleClassName(getClass()).substring(1)+ '-'+poolId.incrementAndGet()+'-']+nextId.incrementAndGet()即nioEventLoopGroup-[poolId]-[nextId]
 */

/**
 * 揭开reactor线程的面纱[https://www.jianshu.com/p/0d0eece6d467|https://www.jianshu.com/p/467a9b41833e|https://www.jianshu.com/p/58fad8e42379]
 * <1>reactor线程启动:SingleThreadEventExecutor#execute()->SingleThreadEventExecutor#startThread()
 * NioEventLoop的run()方法是reactor线程的主体,在第一次添加任务的时候被启动
 * 1.NioEventLoop父类 SingleThreadEventExecutor的execute()方法
 * 外部线程在往任务队列里面添加任务的时候执行 startThread()方法判断reactor线程有没有被启动,如果没有被启动调用doStartThread()方法启动线程再往任务队列里面添加任务
 * SingleThreadEventExecutor执行doStartThread()方法的时候调用内部执行器executor的execute()方法,调用NioEventLoop的run()方法的过程封装成runnable塞到线程中执行
 * 该线程是executor创建对应netty的reactor线程实体,executor默认是ThreadPerTaskExecutor,默认情况ThreadPerTaskExecutor在每次执行execute()方法的时候通过DefaultThreadFactory创建FastThreadLocalThread线程,此线程是netty中的reactor线程实体
 * 2.ThreadPerTaskExecutor:调用NioEventLoopGroup的父类MultithreadEventExecutorGroup构造方法创建ThreadPerTaskExecutor[executor=new ThreadPerTaskExecutor(newDefaultThreadFactory())],通过newChild()方法传递给NioEventLoop[new NioEventLoop(this, executor, (SelectorProvider) args[0],((SelectStrategyFactory) args[1]).newSelectStrategy(), (RejectedExecutionHandler) args[2])]
 * netty的reactor线程添加任务的时候被创建,该线程实体为 FastThreadLocalThread,线程执行主体为NioEventLoop的run()方法
 * <2>reactor线程执行:NioEventLoop#run()
 * reactor线程不断循环三个步骤:
 * 1.首先轮询注册到reactor线程对用的selector上的所有的channel的IO事件
 * select(wakenUp.getAndSet(false));
 * if (wakenUp.get()) {
 * selector.wakeup();
 * }
 * wakenUp表示是否应该唤醒正在阻塞的select操作,netty在进行一次新的loop之前都会将wakeUp被设置成false标识新的一轮loop的开始
 * (1)定时任务截止时间快到中断本次轮询
 * NioEventLoop中reactor线程的select操作是for循环,for循环第一步如果发现当前的定时任务队列有任务的截止时间快到(<=0.5ms)跳出循环,跳出循环之前如果发现目前为止还没有进行过select操作[if (selectCnt == 0)]调用selectNow()方法立即返回不会阻塞,netty里面定时任务队列是按照延迟时间从小到大进行排序,delayNanos(currentTimeNanos)方法即取出第一个定时任务的延迟时间
 * (2)轮询过程中发现有任务加入中断本次轮询
 * netty为了保证任务队列能够及时执行,在进行阻塞select操作的时候会判断任务队列是否为空,如果不为空执行非阻塞select操作跳出循环
 * (3)阻塞式select操作
 * netty任务队列里面队列为空并且所有定时任务延迟时间还未到(大于0.5ms)进行阻塞select操作,截止到第一个定时任务的截止时间
 * 外部线程调用execute方法添加任务调用wakeup方法唤醒selector阻塞即selector.select(timeoutMillis)
 * (4)决定是否中断本次轮询
 * 阻塞select操作结束之后,netty做一系列的状态判断来决定是否中断本次轮询,中断本次轮询的条件有1.轮询到IO事件[selectedKeys!= 0],2.oldWakenUp参数为true,3.用户主动唤醒[wakenUp.get()],4.任务队列里面有任务[hasTasks()],5.第一个定时任务即将要被执行[hasScheduledTasks()]
 * (5)解决jdk的nio空轮询bug
 * bug导致selector一直空轮询最终导致cpu 100% nio server不可用,netty在每次进行 selector.select(timeoutMillis)之前记录开始时间currentTimeNanos,在select之后记录结束时间,判断select操作是否至少持续timeoutMillis秒[time - TimeUnit.MILLISECONDS.toNanos(timeoutMillis)>=currentTimeNanos],如果持续的时间大于等于timeoutMillis说明是一次有效的轮询重置selectCnt标志,否则表明该阻塞方法并没有阻塞这么长时间可能触发jdk的空轮询bug,当空轮询的次数超过阀值SELECTOR_AUTO_REBUILD_THRESHOLD[默认是512]调用rebuildSelector()方法重建selector
 * rebuildSelector()方法fix空轮询bug的过程是new新的selector将之前注册到老的selector上的的channel重新转移到新的selector上,通过openSelector()方法创建新的selector执行死循环,只要执行过程出现过并发修改selectionKeys异常重新开始转移,转移步骤:1.拿到有效的key,2.key.cancel()取消该key在旧的selector上的事件注册,3.key.channel().register(newSelector, interestOps, a)将该key对应的channel注册到新的selector上,4.((AbstractNioChannel) a).selectionKey = newKey;重新绑定channel和新的key的关系,转移完成之后将原有的selector废弃后面所有的轮询都是在新的selector进行
 * reactor线程select操作步骤不断轮询是否有IO事件发生并且在轮询的过程中不断检查是否有定时任务和普通任务,保证netty的任务队列中的任务得到有效执行,轮询过程顺带用计数器避开jdk空轮询的bug
 * 2.处理产生网络IO事件的channel
 * processSelectedKeys();
 * selectedKeys是SelectedSelectionKeySet类对象,NioEventLoop的openSelector()方法创建SelectedSelectionKeySet类对象[final SelectedSelectionKeySet selectedKeySet=new SelectedSelectionKeySet()],通过反射将selectedKeys与 sun.nio.ch.SelectorImpl的两个HashSet类型的field即selectedKeys和publicSelectedKeys绑定,selector在调用select()方法的时候如果有IO事件发生往里面的两个field塞相应的selectionKey并且通过反射将selector的两个field替换掉
 * SelectedSelectionKeySet类继承AbstractSet,add()方法将SelectionKey塞到数组的逻辑尾部,更新数组的逻辑长度+1,如果数组的逻辑长度等于数组的物理长度将数组扩容,等数组的长度足够长每次在轮询到nio事件的时候需要O(1)的时间复杂度将 SelectionKey塞到 set中,jdk底层使用的hashSet需要O(lgn)的时间复杂度
 * 处理IO事件有两种选择:一种是processSelectedKeysOptimized()处理优化过的selectedKeys,一种是processSelectedKeysPlain()正常的处理
 * processSelectedKeysOptimized()方法分为三个步骤:
 * (1)取出IO事件以及对应的channel类
 * 拿到当前SelectionKey之后将selectedKeys[i]置为null避免造成数组尾部的selectedKeys[i]对应的SelectionKey无法回收即SelectionKey的attachment是GC Root可达的,GC不掉导致内存泄漏
 * (2)处理该channel
 * 获取SelectionKey的attachment判断是否为AbstractNioChannel实例,netty的轮询注册机制是将AbstractNioChannel内部的jdk类SelectableChannel对象注册到Selector对象并且将AbstractNioChannel作为SelectableChannel对象的attachment附属,轮询出SelectableChannel有IO事件发生时取出AbstractNioChannel进行后续操作
 * SelectionKey的attachment是AbstractNioChannel实例调用processSelectedKey()方法boss NioEventLoop轮询到的是连接事件通过pipeline将连接扔给worker NioEventLoop处理,worker NioEventLoop轮询到的是io读写事件通过pipeline将读取到的字节流传递给每个channelHandler来处理
 * SelectionKey的attachment是NioTask[用于当SelectableChannel注册到selector的时候执行任务]实例调用processSelectedKey()方法通过NioTask的channelReady()方法处理IO事件
 * (3)判断是否该再来次轮询
 * channel从selector移除的时候调用cancel()方法取消SelectionKey并且当取消的SelectionKey数量到达CLEANUP_INTERVAL[默认值为256]设置needsToSelectAgain为true即每个NioEventLoop每隔256个channel从selector移除标记needsToSelectAgain为true
 * channel从selector移除每满256次needsToSelectAgain为true将selectedKeys数组全部清空方便jvm垃圾回收,重新调用selectAgain()重新填装 selectionKey,每隔256次channel断线重新清理SelectionKey保证现存的SelectionKey及时有效
 * netty使用数组替换掉jdk原生的HashSet来保证IO事件的高效处理,每个SelectionKey绑定AbstractChannel类对象作为attachment,处理每个SelectionKey的时候找到AbstractChannel通过pipeline的方式将处理串行到ChannelHandler回调到用户方法
 * 3.处理任务队列
 * runAllTasks();
 * netty task常见使用场景:
 * 1.用户自定义普通任务
 * ctx.channel().eventLoop().execute(new Runnable() {
 *  @Override
 *  public void run() {
 *  }
 * });
 * SingleThreadEventExecutor的execute()方法调用addTask()方法使用offerTask()方法将task保存到taskQueue,如果调用offerTask()方法失败调用reject()方法通过默认RejectedExecutionHandler抛异常
 * 调用NioEventLoop的newTaskQueue()方法初始化taskQueue[PlatformDependent.newMpscQueue(maxPendingTasks)],taskQueue在NioEventLoop里默认是mpsc队列即多生产者单消费者队列,使用mpsc队列方便将外部线程的task聚集,reactor线程内部用单线程来串行执行
 * 2.非当前reactor线程调用channel的各种方法
 * // non reactor thread
 * channel.write(...);
 * 外部线程调用AbstractChannelHandlerContext的write()方法的时候,executor.inEventLoop()返回false将write操作封装成WriteTask调用safeExecute()方法通过SingleThreadEventExecutor的execute()方法执行任务
 * 用户自定义普通任务场景的调用链的发起线程是reactor线程,非当前reactor线程调用channel的各种方法场景的调用链的发起线程是用户线程,用户线程可能有很多个,显然多个线程并发写taskQueue可能出现线程同步问题
 * push系统一般在业务线程里面根据用户的标识找到对应的channel引用调用write()方法向用户推送消息
 * 3.用户自定义定时任务
 *ctx.channel().eventLoop().schedule(new Runnable() {
 *  @Override
 *  public void run(){
 *  }
 * },  60, TimeUnit.SECONDS);
 * AbstractScheduledEventExecutor的schedule()方法将用户自定义任务包装成ScheduledFutureTask,通过scheduledTaskQueue()方法获取优先级队列scheduledTaskQueue调用add()方法将定时任务ScheduledFutureTask加入到队列,使用优先级队列不需要考虑多线程的并发是因为调用链的发起方是reactor线程不会存在多线程并发问题,外部线程调用schedule()方法将优先级队列scheduledTaskQueue添加定时任务ScheduledFutureTask逻辑封装成Runnable普通任务task[添加[添加定时任务]的任务,非添加定时任务,对 PriorityQueue的访问变成单线程即只有reactor线程]
 * 优先级队列按一定的顺序来排列内部元素,内部元素必须是可以比较的,每个元素都是定时任务ScheduledFutureTask,ScheduledFutureTask的compareTo()方法比较两个定时任务首先比较任务的截止时间,截止时间相同的情况比较id即任务添加的顺序,如果id再相同的话抛Error,保证执行定时任务最近截止时间的任务忧先执行
 * 定时任务分为三种类型:1.若干时间后执行一次,2.每隔一段时间执行一次,3.每次执行结束隔一定时间再执行一次,ScheduledFutureTask使用periodNanos成员变量区分定时任务类型[0-no repeat, >0-repeat at fixed rate, <0- repeat with fixed delay],periodNanos等于0表示若干时间后执行一次定时任务类型执行完定时任务结束,periodNanos不等于0执行任务再区分哪种类型的定时任务,periodNanos大于0表示是以固定频率执行定时任务和任务的持续时间无关,设置该任务的下一次截止时间为本次的截止时间加上间隔时间periodNanos,periodNanos小于0表示每次任务执行完毕间隔多长时间再次执行,截止时间为当前时间加上间隔时间,-p表示加上一个正的间隔时间,将当前任务对象再次加入到队列实现任务的定时执行
 * runAllTasks()方法参数timeoutNanos表示该方法最多执行这么长时间,reactor线程如果在此停留的时间过长将积攒许多的IO事件无法处理最终导致大量客户端请求阻塞,默认情况控制内部队列的执行时间
 * (1)调用fetchFromScheduledTaskQueue()方法从scheduledTaskQueue将到期的定时任务转移到taskQueue[mpsc queue],当taskQueue调用offer()方法失败需要把从scheduledTaskQueue里面取出来的定时任务重新添加回去,从scheduledTaskQueue获取定时任务通过pollScheduledTask()方法在当前任务的截止时间已经到达拉取
 * (2)从taskQueue取出第一个任务用reactor线程传入的超时时间 timeoutNanos[final long deadline=ScheduledFutureTask.nanoTime()+timeoutNanos]计算本次任务循环的截止时间,使用runTasks和lastExecutionTime时刻记录任务的状态
 * (3)循环调用safeExecute()确保任务安全执行忽略任何异常,将已运行任务 runTasks加1,每隔0x3F任务即每执行完64个任务判断当前时间是否超过本次reactor任务循环的截止时间,超过任务截止时间跳出循环,未超过任务截止时间继续从taskQueue获取任务执行
 * (4)调用afterRunningAllTasks()方法循环从收尾任务队列tailTasks拉取收尾任务执行,通过父类SingleTheadEventLoop的executeAfterEventLoopIteration()方法向tailTasks中添加收尾任务
 * 当前reactor线程调用当前eventLoop执行任务直接执行,否则添加到任务队列稍后执行;netty内部的任务分为普通任务和定时任务,分别落地到MpscQueue和PriorityQueue;netty每次执行任务循环之前将已经到期的定时任务从PriorityQueue转移到MpscQueue;netty每隔64个任务检查一下是否该退出任务循环
 */

/**
 * 服务端启动全解析[https://www.jianshu.com/p/c5068caab217]
 * ServerBootstrap的bind()方法通过端口号创建InetSocketAddress对象调用bind(localAddress)方法,使用validate()方法验证服务启动需要的必要参数,调用doBind()方法启动服务:
 * 1.调用initAndRegister()方法初始化注册Channel
 * (1)通过ChannelFactory即调用channel(channelClass)方法构造ReflectiveChannelFactory的newChannel()方法创建Channel,通过反射的方式调用默认构造函数创建 NioServerSocketChannel对象,NioServerSocketChannel默认构造函数通过SelectorProvider.openServerSocketChannel()创建服务端Channel,创建id[Channel的唯一标识],unsafe,pipeline,赋值成员变量ch为创建NioServerSocketChannel,readInterestOp为SelectionKey.OP_ACCEPT,设置ServerSocketChannel为非阻塞模式,,创建NioServerSocketChannelConfig
 * (2)调用init()方法初始化服务端Channel使用options0()和attrs0()方法获取options和attrs,设置options和attrs注入到ChannelConfig/Channel,获取成员变量childOptions和childAttrs设置给新连接对应的Channel,向ServerSocketChannel的流水线处理器添加ServerBootstrapAcceptor[接入器专门接收新连接,把新的请求扔给事件循环器]
 * (3)通过EventLoopGroup的register()方法调用服务端Channel的Unsafe register()方法将EventLoop事件循环器绑定到NioServerSocketChannel,调用 register0()方法使用doRegister()方法将ServerSocketChannel注册到Selector设置ops为0,调用Pipeline的invokeHandlerAddedIfNeeded()方法调用自定义Handler的handlerAdded()方法,fireChannelRegistered()方法调用自定义Handler的channelRegistered()方法,
 * 2.调用doBind0()方法通过包装Runnable进行异步绑定
 * 调用Pipeline的bind()方法使用tail节点的bind()方法,最终调用head节点的bind()方法使用NioMessageUnsafe的bind()方法通过doBind()方法绑定ServerSocketChannel端口,判断Channel是否被激活发起Pipeline的fireChannelActive()方法调用,使用AbstractNioUnsafe的doBeginRead()方法设置selectionKey的interestOps为0|SelectionKey.OP_ACCEPT
 * 服务端启动过程:
 * 1.设置启动类参数,最重要的就是设置channel
 * 2.创建server对应的channel,创建各大组件包括ChannelConfig,ChannelId,ChannelPipeline,ChannelHandler,Unsafe等
 * 3.初始化server对应的channel,设置attr,option以及设置子channel的attr,option,给server的channel添加新channel接入器并且触发addHandler,register等事件
 * 4.调用到jdk底层做端口绑定并触发active事件,active触发的时候真正做服务端口绑定
 */

/**
 * 新连接接入全解析[https://www.jianshu.com/p/0242b1d4dd21]
 * 两种类型的Reactor线程:一种类型的Reactor线程是boss线程组专门用来接收新的连接,然后封装成Channel对象扔给worker线程组;还有一种类型的Reactor线程是worker线程组专门用来处理连接的读写,不管是boss线程还是worker线程,所做的事情均分为三个步骤:(1)轮询注册在selector上的IO事件;(2)处理IO事件;(3)执行异步Task,对于boss线程来说第一步轮询出来的基本都是accept事件表示有新的连接,worker线程轮询出来的基本都是read/write事件表示网络的读写事件
 * 新连接的建立:
 * 1.检测到有新连接进入
 * 当服务端绑启动之后服务端的Channel已经注册到boss reactor线程,Reactor不断检测有新的事件直到检测出有accept事件发生,processSelectedKey()方法表示boss reactor线程已经轮询到SelectionKey.OP_ACCEPT事件,说明有新的连接进入此时将调用Channel的 Unsafe来进行实际的操作
 * 2.将新的连接注册到worker线程组[注册到reactor线程]
 * 调用NioMessageUnsafe的read()方法获取Channel对应的Pipeline和RecvByteBufAllocator.Handle,调用doReadMessages()方法使用jdk底层Channel的accept()方法获取jdk底层nio创建的Channel,将jdk的SocketChannel封装成自定义的NioSocketChannel设置readInterestOp为SelectionKey.OP_READ表示Channel关心的事件是 SelectionKey.OP_READ,将 SelectionKey.OP_READ事件注册到selector设置该通道为非阻塞模式,添加到readBuf容器不断地读取消息,调用 Pipeline的fireChannelRead()方法通过head->unsafe->ServerBootstrapAcceptor的调用链调用ServerBootstrapAcceptor的channelRead()方法将每条新连接经过一层服务端Channel的洗礼,清理容器触发 Pipeline的fireChannelReadComplete()方法调用
 * Channel继承Comparable表示Channel是可以比较的对象,Channel继承AttributeMap表示Channel是使用channel.attr(...)方法绑定属性的对象,ChannelOutboundInvoker表示一条Channel可以进行的操作,DefaultAttributeMap用于AttributeMap抽象的默认方法,Channel继承直接使用,AbstractChannel用于实现Channel的大部分方法,构造函数创建出一条Channel的基本组件,,AbstractNioChannel基于AbstractChannel实现nio相关的一些操作,保存jdk底层的SelectableChannel并且在构造函数中设置Channel为非阻塞,NioServerSocketChannel/NioSocketChannel对应着服务端接受新连接过程/新连接读写过程
 * Pipeline流水线的开始是HeadContext,流水线的结束是TailContext,HeadContext调用Unsafe做具体的操作,TailContext用于向用户抛出Pipeline中未处理异常以及对未处理消息的警告,NioSocketChannel的Pipeline对应的处理器为 head->ChannelInitializer->tail最终调用ChannelInitializer的handlerAdded()方法,handlerAdded()方法调用自定义的 initChannel()方法,使用remove(ctx)将自身删除
 * 事件执行器选择器Chooser通过DefaultEventExecutorChooserFactory创建,在创建Reactor线程选择器的时候判断Reactor线程的个数是否为2的幂,如果Reactor线程的个数是2的幂创建PowerOfTowEventExecutorChooser,否则创建GenericEventExecutorChooser,两种类型的选择器在选择Reactor线程的时候通过Round-Robin的方式选择Reactor线程,唯一不同的是PowerOfTowEventExecutorChooser是通过与运算,GenericEventExecutorChooser是通过取余运算,与运算的效率要高于求余运算
 * 3.注册新连接的读事件
 * 调用Pipeline的fireChannelRegistered()方法使用业务Pipeline每个处理器Handler的channelHandlerAdded()方法处理回调,通过Pipeline的fireChannelActive()方法设置readInterestOp对应的事件是 SelectionKey.OP_READ,将SelectionKey.OP_READ事件注册到Selector表示这条通道已经开始处理read事件
 * 新连接接入过程:
 * 1.boos reactor线程轮询到有新的连接进入
 * 2.通过封装jdk底层的channel创建 NioSocketChannel以及一系列的netty核心组件
 * 3.将该条连接通过chooser选择一条worker reactor线程绑定
 * 4.注册读事件开始新连接的读写
 */

/**
 * Pipeline[https://www.jianshu.com/p/6efa9c5fa702|https://www.jianshu.com/p/087b7e9a27a2]
 * Pipeline初始化:
 *                    ----------   ---------
 * Channel-|----| Head |=| Tail |
 *               |    ----------   ---------
 *               |           Pipeline
 *               |---NioSocketChannelConfig
 *               |---ChannelId
 *               |---Unsafe
 *               |--SelectableChannel
 *             Head                                   Tail
 * ---------------------------          ---------------------------
 * | ------------------------ |----->| ------------------------ |
 * | | channelHandler | |         | | channelHandler | |
 * | ------------------------ |         | ------------------------ |        Pipeline
 * |         pipeline          |<-----|         pipeline          |
 * |          unsafe           |         ---------------------------
 * ---------------------------         ChannelHandlerContext
 * ChannelHandlerContext
 * Pipeline保存Channel的引用,每个节点是ChannelHandlerContext对象,每个context节点保存包裹的执行器ChannelHandler执行操作所需要的上下文,默认情况Pipeline有两个节点即head和tail
 * Pipeline添加节点:
 * bootstrap.childHandler(new ChannelInitializer<SocketChannel>() {
 *      @Override
 *      public void initChannel(SocketChannel ch) throws Exception {
 *          ChannelPipeline p = ch.pipeline();
 *          p.addLast(new Spliter())
 *          p.addLast(new Decoder());
 *          p.addLast(new BusinessHandler())
 *          p.addLast(new Encoder());
 *      }
 * });
 * -------------         --------------         ----------------         --------------------------         ----------------         -------------
 * | ---------- |----->| ----------- |----->| ------------- |----->| ----------------------- |----->| ------------- |----->| ---------- |
 * | | head | |         | | spliter | |        | | decoder | |        | | businessHandler| |        | | encoder | |        | |   tail   | |
 * | ---------- |         | ----------- |        | ------------- |         | ----------------------- |        | ------------- |         | ---------- |
 * | pipeline |<-----|  pipeline |<-----|   pipeline  |<-----|        pipeline          |<-----|   pipeline  |<-----| pipeline |
 * |  unsafe  |         --------------         ----------------         --------------------------         ----------------         -------------
 * -------------
 * 首先使用spliter将来源TCP数据包拆包,然后将拆出来的包进行decoder,传入业务处理器BusinessHandler,业务处理完encoder输出
 * Pipeline两种不同类型的节点:ChannelInboundHandler处理inBound事件最典型的就是读取数据流加工处理,ChannelOutboundHandler处理outBound事件比如当调用writeAndFlush()类方法经过该类型的Handler,ChannelHandlerContext之间都是通过双向链表连接,addLast()方法使用synchronized是为了防止多线程并发操作Pipeline底层的双向链表
 * (1)检查是否有重复Handler:使用成员变量added标识Channel是否已经添加,如果当前要添加的Handler是非共享的并且已经添加过抛异常,否则标识该Handler已经添加,Handler如果是sharable的无限次添加到Pipeline,客户端如果要使得Handler被共用需要加@Sharable标注即可,isSharable()方法通过该Handler对应的类是否标注@Sharable来实现的,使用ThreadLocal缓存Handler的状态
 * (2)创建节点:调用filterName()方法传参name为null创建默认name[规则为简单类名#0]否则检查是否有重名,使用 FastThreadLocal变量来缓存Handler的类和默认名称的映射关系,在生成name的时候查看缓存中有没有生成过默认name(简单类名#0),如果没有生成调用generateName0()方法生成默认name加入缓存,调用context0()方法查找Pipeline里面有没有对应的context,检查name是否和已有的name有冲突,链表遍历每个ChannelHandlerContext只要发现context的名字与待添加的name相同返回context最后抛异常,如果context0(name)!=null成立说明现有的context里面已经有默认name从简单类名#1往上一直找直到找到一个唯一的name,调用newContext()方法创建DefaultChannelHandlerContext,使用isInbound()/isOutbound()方法判断ChannelHandlerContext属于inBound还是outBound设置成员变量inbound/outBound
 * (3)添加节点:
 *             Head                                                                          Tail
 * ---------------------------         ---------------------------         ---------------------------
 * | ------------------------ |----->| ------------------------ |--X-->| ------------------------ |
 * | | channelHandler | |         | | channelHandler | |        | | channelHandler | |
 * | ------------------------ |         | ------------------------ |        | ------------------------ |
 * |         pipeline          |<-----|         pipeline         |<-X--|         pipeline          |
 * |          unsafe           |         --------------------------          ---------------------------
 * ---------------------------                  /\              \|/ 3               4 /|\        /\
 *                                                    |  1     ---------------------------   2   |
 *                                                    |         | ------------------------ |        |
 *                                                    --------| | channelHandler | |--------
 *                                                              | ------------------------ |
 *                                                              |         pipeline          |
 *                                                              ---------------------------
 *                                                         |
 *                                                        \/
 *             Head                                                                        newCtx                                 Tail
 * ---------------------------         ---------------------------         ---------------------------         ---------------------------
 * | ------------------------ |----->| ------------------------ |----->| ------------------------ |----->| ------------------------ |
 * | | channelHandler | |         | | channelHandler | |        | | channelHandler | |         | | channelHandler | |
 * | ------------------------ |         | ------------------------ |        | ------------------------ |         | ------------------------ |
 * |         pipeline          |<-----|         pipeline         |<-----|         pipeline          |<-----|         pipeline          |
 * |          unsafe           |         --------------------------         ----------------------------         ---------------------------
 * ---------------------------
 * (4)回调用户方法:添加Pipeline的ChannelHandlerContext节点完成回调自定义handlerAdded()方法通过CAS修改设置ChannelHandlerContext节点的状态设置为REMOVE_COMPLETE[说明该节点已经被移除]/ADD_COMPLETE
 * Pipeline删除节点:
 * (1)找到待删除的节点:调用context()方法通过依次遍历双向链表的方式直到ChannelHandlerContext 的Handler和当前Handler相同获取ChannelHandlerContext节点
 * (2)调整双向链表指针删除:
 *             Head                                                        ------------------>---------------               Tail
 * ---------------------------         --------------------------- /  1    -----------------------       \ ---------------------------
 * | ------------------------ |----->| ------------------------ |--X-->| -------------------- |----->| ------------------------ |
 * | | channelHandler | |         | | channelHandler | |        | | authHandler | |         | | channelHandler | |
 * | ------------------------ |         | ------------------------ |        | -------------------- |         | ------------------------ |
 * |         pipeline          |<-----|         pipeline         |<-----|       pipeline       |<--X--|         pipeline          |
 * |          unsafe           |         -------------------------- \      -----------------------    2   / ---------------------------
 * ---------------------------                                           ------------------<----------------
 *                                                          |
 *                                                         \/
 *             Head                                                                          Tail
 * ---------------------------         ---------------------------         ---------------------------
 * | ------------------------ |----->| ------------------------ |----->| ------------------------ |
 * | | channelHandler | |         | | channelHandler | |        | | channelHandler | |
 * | ------------------------ |         | ------------------------ |        | ------------------------ |
 * |         pipeline          |<-----|         pipeline         |<-----|         pipeline          |
 * |          unsafe           |         --------------------------          ---------------------------
 * ---------------------------
 * (3)回调用户函数:删除Pipeline的ChannelHandlerContext节点完成回调自定义handlerRemoved()方法设置ChannelHandlerContext节点的状态设置为REMOVE_COMPLETE
 * 新连接创建过程创建Channel,创建Channel的过程创建该Channel对应的Pipeline,创建完Pipeline之后自动给该Pipeline添加两个节点即ChannelHandlerContext,ChannelHandlerContext有Pipeline和Channel所有的上下文信息.Pipeline是双向个链表结构,添加和删除节点均只需要调整链表结构,Pipeline中的每个节点包着具体的处理器ChannelHandler,节点根据ChannelHandler的类型是ChannelInboundHandler还是ChannelOutboundHandler判断该节点属于in还是out或者两者都是
 * Unsafe到底是干什么的:
 * (1)Unsafe定义
 * Unsafe在Channel定义属于Channel的内部类表明Unsafe和Channel密切相关,Unsafe方法按功能分为分配内存,Socket四元组信息,注册事件循环,绑定网卡端口,Socket的连接和关闭,Socket的读写
 * (2)Unsafe继承结构
 * NioUnsafe增加访问底层jdk的SelectableChannel的功能,定义从SelectableChannel读取数据的read()方法,AbstractUnsafe实现大部分Unsafe的功能,AbstractNioUnsafe主要是通过代理到其外部类AbstractNioChannel获取与jdk nio相关的信息比如SelectableChannel,SelectionKey等等,NioSocketChannelUnsafe和NioByteUnsafe实现IO的基本操作读和写与jdk底层相关,NioMessageUnsafe和NioByteUnsafe是处在同一层次的抽象,将新连接的建立当作io操作处理,message的含义是指SelectableChannel,读的意思就是accept一个SelectableChannel,写的意思是针对一些无连接的协议比如UDP来操作的
 * (3)Unsafe分类
 * 两种类型的Unsafe分类:与连接的字节数据读写相关的NioByteUnsafe和与新连接建立操作相关的NioMessageUnsafe,NioMessageUnsafe的读操作委托到外部类NioSocketChannel通过doReadMessages()方法调用jdk的accept()方法新建立一条连接,NioMessageUnsafe的写操作委托到外部类NioSocketChannel通过doWriteBytes()方法将netty的ByteBuf中的字节数据写到jdk的SelectableChannel
 * Pipeline的head:
 * head节点在Pipeline里第一个处理IO事件,新连接接入和读事件调用processSelectedKey()检测到,读操作直接依赖Unsafe进行,NioByteUnsafe读连接字节数据流:(1)获取Channel的ChannelConfig拿到ByteBuf分配器,使用分配器来分配一个ByteBuf,ByteBuf是字节数据载体,读取的数据都读到这个对象里面,(2)将Channel里的数据读取到ByteBuf,(3)调用 Pipeline的fireChannelRead()方法触发Pipeline的读事件,将读取到的ByteBuf进行事件的传播,从head节点开始传播至整个Pipeline,(4)读取数据完成调用 Pipeline的fireChannelReadComplete()方法触发Pipeline的读完成事件
 * head节点继承ChannelHandlerContext类实现ChannelInboundHandler/ChannelOutboundHandler接口,传播读写事件通过ctx.fireChannelRead(msg)将读事件传播下去,执行读写操作例如调用writeAndFlush()等方法最终委托到Unsafe执行,当一次数据读完调用channelReadComplete()方法将事件传播下去并且向Reactor线程注册读事件即调用readIfIsAutoRead()方法,默认情况Channel默认开启自动读取模式即只要Channel是active的读完数据继续向selector注册读事件不断读取数据,最终通过Pipeline传递到head节点调用read()方法委托NioByteUnsafe的beginRead()方法,使用doBeginRead()方法获取处理过的SelectionKey如果SelectionKey移除readInterestOp操作只有在三次握手成功后将readInterestOp注册到SelectionKey,head节点的作用是作为Pipeline的头节点开始传递读写事件调用unsafe进行实际的读写操作
 * Pipeline的inBound事件传播:
 * 三次握手成功调用Pipeline的fireChannelActive()方法,以head节点为参数调用AbstractChannelHandlerContext的invokeChannelActive()方法,为了确保线程的安全性将该操作在Reactor线程中被执行调用HeadContext的fireChannelActive()方法,使用findContextInbound()方法遍历双向链表的下一个节点,直到下一个节点为inBound获取下一个inbound节点,执行invokeChannelActive()方法递归调用直到最后inBound节点-tail节点,tail节点的channelActive()方法为空结束调用
 * Pipeline的tail:
 * tail节点作用是终止事件的传播,exceptionCaught()方法异常传播机制是如果用户自定义节点没有处理最终落到tail节点发起警告,channelRead()方法发现字节数据(ByteBuf)或者decoder之后的业务对象Pipeline流转过程没有被消费落到tail节点发起警告,tail节点作用是结束事件传播,并且对一些重要的事件做善意提醒
 * Pipeline的outBound事件传播:
 * Channel大部分outBound事件是从tail节点开始往外传播, writeAndFlush()方法是tail节点继承的方法,使用findContextOutbound()方法反方向遍历Pipeline中的双向链表直到第一个outBound节点next获取下一个outBound节点,调用invokeWriteAndFlush()方法调用该节点ChannelHandler的write()方法,使用outBound类型的ChannelHandler继承 ChannelOutboundHandlerAdapter,递归调用ChannelHandlerContext的write()方法最终到最后一个outBound节点即head节点的write()方法,实际情况outBound类的节点有特殊类型的节点叫encoder,作用是根据自定义编码规则将业务对象转换成ByteBuf,这类encoder继承自MessageToByteEncoder,MessageToByteEncoder的write()方法调用acceptOutboundMessage()方法判断该encoder是否处理msg对应的类对象,强制转换泛型I对应的是DataPacket,转换过后开辟一段内存调用encode()方法将buf装满数据,如果buf中已被写数据[buf.isReadable()]将该buf往前丢一直传递到head节点被head节点的Unsafe消费掉,如果当前encoder不能处理当前业务对象将该业务对象向前传播直到head节点,处理完释放buf避免堆外内存泄漏
 * Pipeline异常的传播:
 * ---------------------------         ---------------------------         -----------------------------         ---------------------------
 * | ------------------------ |----->| ------------------------ |----->| -------------------------- |----->| ------------------------ |
 * | | channelHandler | |         | | channelHandler | |        | | exceptionHandler | |         | | channelHandler | |
 * | ------------------------ |         | ------------------------ |        | -------------------------- |         | ------------------------ |
 * |         pipeline          |<-----|         pipeline         |<-----|          pipeline           |<-----|         pipeline          |
 * |          unsafe           |         --------------------------         -----------------------------          ---------------------------
 * ---------------------------
 * 异常处理器统一处理Pipeline过程的所有异常,并且一般该异常处理器需要加载自定义节点的最末尾,ExceptionHandler一般继承自ChannelDuplexHandler,标识该节点既是inBound节点又是outBound节点
 * (1)inBound异常的处理:对于每一个节点的数据读取都调用AbstractChannelHandlerContext的invokeChannelRead()方法,该节点最终委托到其内部的ChannelHandler处理channelRead,而在最外层catch整个Throwable捕获用户代码异常使用notifyHandlerException()方法往下传播,捕获异常优先由此Handler的exceptionCaught()方法处理,默认情况如果不覆写此Handler的exceptionCaught()方法调用ChannelInboundHandlerAdapter的exceptionCaught()方法,使用AbstractChannelHandlerContext的fireExceptionCaught()方法如果在自定义Handler中没有处理异常,默认情况该异常将一直传递下去,遍历每一个节点直到最后一个自定义异常处理器ExceptionHandler终结收编异常
 * (2)onBound异常的处理:Channel的writeAndFlush()方法最终调用到节点的 invokeFlush0()方法,invokeFlush0()方法委托其内部的ChannelHandler的flush()方法,假设在当前节点flush的过程发生异常都会被notifyHandlerException()方法捕获轮流找下一个异常处理器,如果异常处理器在pipeline最后面一定会被执行到
 * 在任何节点中发生的异常都会往下一个节点传递最后终究会传递到异常处理器
 * 一个Channel对应一个Unsafe,Unsafe处理底层操作,NioServerSocketChannel对应NioMessageUnsafe, NioSocketChannel对应NioByteUnsafe,inBound事件从head节点传播到tail节点,outBound事件从tail节点传播到head节点,异常传播只会往后传播,而且不分inbound还是outbound节点,不像outBound事件一样会往前传播
 */

/**
 * writeAndFlush全解析[https://www.jianshu.com/p/feaeaab2ce56]
 * pipeline中的标准链表结构:
 * -------------         --------------         ----------------         --------------------------         ----------------         -------------
 * | ---------- |----->| ----------- |----->| ------------- |----->| ----------------------- |----->| ------------- |----->| ---------- |
 * | | head | |         | | spliter | |        | | decoder | |        | | businessHandler| |        | | encoder | |        | |   tail   | |
 * | ---------- |         | ----------- |        | ------------- |         | ----------------------- |        | ------------- |         | ---------- |
 * | pipeline |<-----|  pipeline |<-----|   pipeline  |<-----|        pipeline          |<-----|   pipeline  |<-----| pipeline |
 * |  unsafe  |         --------------         ----------------         --------------------------         ----------------         -------------
 * -------------
 * 数据从head节点流入先拆包然后解码成业务对象最后经过业务Handler处理调用write将结果对象写出去;数据写入先通过tail节点然后通过encoder节点将对象编码成ByteBuf最后将该ByteBuf对象传递到head节点调用底层的Unsafe写到jdk底层管道
 * java对象编码过程:
 * MessageToByteEncoder处理java对象:
 * 1.判断当前Handler是否能够处理写入的消息
 * 2.将对象强制转换成Encoder处理的Response对象
 * 3.分配一个ByteBuf
 * 4.调用Encoder的encode()方法将数据写入ByteBuf
 * 5.释放java对象[当参数msg类型为ByteBuf不需要手动释放]
 * 6.如果buf写入数据把buf传到下一个节点,否则释放buf将空数据传到下一个节点
 * 7.当buf在pipeline中处理完后释放节点
 * Encoder节点分配一个ByteBuf调用encode()方法将java对象根据自定义协议写入到ByteBuf,然后再把ByteBuf传入到下一个节点最终传入到head节点
 * write:写队列:
 * 1.调用assertEventLoop()方法确保write()方法的调用是在reactor线程
 * 2.调用filterOutboundMessage()方法过滤待写入的对象把非ByteBuf对象和FileRegion过滤,把非直接内存转换成直接内存DirectBuffer
 * 3.通过MessageSizeEstimator.Handle的size()方法计算需要写入的ByteBuf的size
 * 4.调用ChannelOutboundBuffer的addMessage(msg, size, promise)方法写入ByteBuf
 * --------------     -------------       --------------
 * |   Entry   |     |   Entry   |       |   Entry   |
 * --------------     -------------       --------------
 *         |                     |                      |
 * flushedEntry  unflushedEntry  tailEntry
 * ChannelOutboundBuffer里面的数据结构是单链表结构,每个节点是 Entry,Entry里面包含待写出ByteBuf以及消息回调promise,涵盖三种类型指针:
 * 1.flushedEntry指针表示第一个被写到操作系统Socket缓冲区中的节点
 * 2.unFlushedEntry指针表示第一个未被写入到操作系统Socket缓冲区中的节点
 * 3.tailEntry指针表示ChannelOutboundBuffer缓冲区的最后一个节点
 * 第一次调用addMessage():fushedEntry指向空,unFushedEntry和tailEntry都指向新加入的节点
 *                                        -------------
 * NULL                             |   Entry   |
 *                                        -------------
 *     |                                         /\
 * flushedEntry  unflushedEntry  tailEntry
 * 第二次调用addMessage():
 *                         -------------      -------------
 * NULL              |   Entry   |-->|   Entry   |
 *                         -------------      -------------
 *     |                           |                    |
 * flushedEntry  unflushedEntry  tailEntry
 * 第n次调用addMessage():flushedEntry指针一直指向空表示现在还未有节点需要写出到Socket缓冲区,unFushedEntry之后有n个节点表示当前还有n个节点尚未写出到Socket缓冲区
 *                         -------------                 --------------
 * NULL              |   Entry   |-->... ...-->|   Entry   |
 *                         -------------n-2个节点 --------------
 *     |                           |                                |
 * flushedEntry  unflushedEntry               tailEntry
 * flush:刷新写队列:
 *不管调用channel.flush()还是ctx.flush()最终都落地到pipeline中的head节点调用AbstractUnsafe的flush()方法
 * 1.调用addFlush()方法获取unflushedEntry指针将flushedEntry指向unflushedEntry所指向的节点:
 * -------------                 --------------
 * |   Entry   |-->... ...-->|   Entry   |            NULL
 * -------------n-2个节点 --------------
 *        |                                 |                       |
 * flushedEntry               tailEntry       unflushedEntry
 * 2.调用 flush0()方法使用AbstractNioByteChannel的doWrite()方法刷新写队列:
 * (1)调用current()方法获取第一个需要flush的节点的数据
 * (2)获取自旋锁的迭代次数writeSpinCount
 * (3)通过自旋的方式将ByteBuf写出到jdk nio的Channel
 * (4)获取当前flushedEntry指向节点,拿到该节点的回调对象ChannelPromise,调用removeEntry()方法删除当前节点:
 * -------------      -------------                 --------------
 * |      e       |-->|   Entry   |-->... ...-->|   Entry   |            NULL
 * -------------      -------------n-1个节点 --------------
 *                              |                                 |                       |
 *                       flushedEntry               tailEntry       unflushedEntry
 * 释放当前节点内存,调用safeSuccess()进行回调自定义的operationComplete()方法,使用recycle()方法回收当前节点
 * writeAndFlush:写队列并刷新:
 * writeAndFlush()方法在Handler调用之后最终会落到TailContext节点,通过参数flush表示调用invokeWriteAndFlush()方法或者invokeWrite()方法,invokeWriteAndFlush()基本等价于write()方法再调用flush()方法
 * (1)pipeline中的编码器原理是创建一个ByteBuf,将java对象转换为ByteBuf,然后再把ByteBuf继续向前传递
 * (2)调用write()方法并没有将数据写到Socket缓冲区中,而是写到了一个单向链表的数据结构中,flush()方法才是真正的写出
 * (3)writeAndFlush()等价于先将数据写到Netty的缓冲区,再将Netty的缓冲区里的数据写到Socket缓冲区,写的过程使用自旋锁保证写成功
 * (4)Netty的缓冲区中的ByteBuf为DirectByteBuf
 */

/**
 * 拆包器的奥秘[https://www.jianshu.com/p/dc26e944da95]
 * 拆包的原理:
 * 基本原理是不断从TCP缓冲区读取数据,每次读取完需要判断是否为完整的数据包,1.如果当前读取的数据不足以拼接成一个完整的业务数据包,那就保留该数据,继续从tcp缓冲区中读取,直到得到一个完整的数据包2.如果当前读到的数据加上已经读取的数据足够拼接成一个数据包,那就将已经读取的数据拼接上本次读取的数据,够成一个完整的业务数据包传递到业务逻辑,多余的数据仍然保留,以便和下次读到的数据尝试拼接
 * Netty拆包基类ByteToMessageDecoder内部有累加器每次读取到数据不断累加,尝试对累加到的数据进行拆包拆成一个完整的业务数据包,ByteToMessageDecoder定义两个累加器:MERGE_CUMULATOR[默认累加器,原理是每次将读取到的数据通过内存拷贝的方式拼接到一个大的字节容器cumulation,调用ByteBuf的writeBytes()方法将新读取到的数据累加到字节容器里,为了防止字节容器大小不够在累加之前调用expandCumulation()方法通过内存拷贝操作进行扩容处理,累加器新增大小即新读取数据大小]和COMPOSITE_CUMULATOR
 *每次从TCP缓冲区读取到数据调用ByteToMessageDecoder的channelRead()方法实现抽象的拆包过程:
 * 1.累加数据:判断当前字节容器cumulation是否为空,当前字节容器cumulation无数据通过内存拷贝将字节容器的指针指向新读取的数据,否则调用累加器cumulator的cumulate()方法累加数据至字节容器
 * 2.将累加到的数据传递给业务进行拆包:调用callDecode()方法将字节容器的数据拆分成业务数据包塞到业务数据容器CodecOutputList out,记录字节容器待拆字节长度oldInputLength调用decode()方法ByteBuf解码根据不同协议传参当前读取到未被消费的数据和业务协议包容器进行拆包,如果发现没有拆到一个完整的数据包分两种情况:(1)拆包器什么数据也没读取可能数据还不够业务拆包器处理直接break等待新的数据,(2)拆包器已读取部分数据说明解码器仍然在工作继续解码,业务拆包完成发现没有读取任何数据抛Runtime异常 DecoderException
 * 3.清理字节容器:业务拆包完成从字节容器中取走数据,字节容器依然保留字节内存空间,字节容器每次累加字节数据的时候将字节数据追加到尾部,字节容器不做清理造成时间长OOM现象,每次读取完数据调用channelReadComplete()方法清理字节容器,当发送端发送数据过快channelReadComplete()方法可能很久才被调用一次,如果一次数据读取完毕[接收端在某个时间不再接受到数据为止]之后发现仍然没有拆到一个完整的用户数据包,即使该channel的设置为非自动读取也会触发一次读取操作ctx.read(),该操作重新向selector注册op_read事件以便于下一次能读到数据之后拼接成一个完整的数据包,为了防止发送端发送数据过快在每次读取到一次数据业务拆包之后对字节字节容器做清理,如果字节容器当前已无数据可读取销毁字节容器并且标注当前字节容器一次数据也没读取,如果连续16次[discardAfterReads的默认值]字节容器中仍然有未被业务拆包器读取的数据调用discardSomeReadBytes()方法做一次压缩将有效数据段整体移到容器首部
 * 4.传递业务数据包给业务解码器处理:使用成员变量decodeWasNull 标识本次读取数据是否拆到一个业务数据包,调用 fireChannelRead()方法将拆到的业务数据包都传递到后续的handler,把完整的业务数据包传递到后续的业务解码器进行解码处理业务逻辑
 * 行拆包器LineBasedFrameDecoder基于行分隔符的拆包器可以同时处理 \n以及\r\n两种类型的行分隔符,调用decode()方法解析字节容器cumulation数据把数据包添加到CodecOutputList:
 * 1.找到换行符位置:调用findEndOfLine()方法循环遍历查找第一个 \n 的位置,如果\n前面的字符为\r返回\r的位置
 * 2.非discarding模式的处理:成员变量discarding表示当前拆包是否属于丢弃模式,第一次拆包不在discarding模式
 * (1)非discarding模式下找到行分隔符的处理:
 * [1]首先新建一个帧计算一下当前包的长度和分隔符的长度(因为有两种分隔符)
 * [2]然后判断需要拆包的长度是否大于该拆包器允许的最大长度(maxLength),该参数在构造函数被传递进来,如果超出允许的最大长度将这段数据抛弃返回null
 * [3]最后将一个完整的数据包取出,如果构造本解包器的时候指定stripDelimiter为false即解析出来的包包含分隔符,默认为不包含分隔符
 * (2)非discarding模式下未找到分隔符的处理:首先取得当前字节容器可读字节个数,接着判断是否已经超过可允许的最大长度,如果没有超过返回null字节容器中的数据没有任何改变,否则需要进入丢弃模式
 * 使用成员变量 discardedBytes 表示已经丢弃多少数据,将字节容器的读指针移到写指针意味着丢弃这一部分数据,设置成员变量discarding为true表示当前处于丢弃模式,如果设置failFast直接抛出异常,默认情况failFast为false即安静得丢弃数据
 * 3.discarding模式的处理:
 * (1)discarding模式下找到行分隔符:discarding模式如果找到分隔符计算出分隔符的长度,把分隔符之前的数据包括分隔符全部丢弃,经过丢弃后面有可能是正常的数据包,下一次解包的时候就会进入正常的解包流程
 * (2)discarding模式下未找到行分隔符:当前还在丢弃模式没有找到行分隔符意味着当前一个完整的数据包还没丢弃完,当前读取的数据是丢弃的一部分直接丢弃
 * 特定分隔符拆包DelimiterBasedFrameDecoder传参分隔符列表,数据会按照分隔符列表进行拆分
 */

/**
 * LengthFieldBasedFrameDecoder[https://www.jianshu.com/p/a0a51fd79f62]
 * LengthFieldBasedFrameDecoder的用法:
 * 1.基于长度的拆包
 * BEFORE DECODE (14 bytes)         AFTER DECODE (14 bytes)
 * +--------+----------------+      +--------+----------------+
 * | Length | Actual Content |----->| Length | Actual Content |
 * | 0x000C | "HELLO, WORLD" |      | 0x000C | "HELLO, WORLD" |
 * +--------+----------------+      +--------+----------------+
 * 前面几个字节表示数据包的长度(不包括长度域),后面是具体的数据,拆完之后数据包是一个完整的带有长度域的数据包(之后即可传递到应用层解码器进行解码)
 * 创建LengthFieldBasedFrameDecoder:new LengthFieldBasedFrameDecoder(Integer.MAX, 0, 4);第一个参数是maxFrameLength表示包的最大长度,超出包的最大长度做特殊处理,第二个参数指的是长度域的偏移量lengthFieldOffset,在这里是0表示无偏移,第三个参数指的是长度域长度lengthFieldLength,这里是4表示长度域的长度为4
 * 2.基于长度的截断拆包
 * BEFORE DECODE (14 bytes)         AFTER DECODE (12 bytes)
 * +--------+----------------+      +----------------+
 * | Length | Actual Content |----->| Actual Content |
 * | 0x000C | "HELLO, WORLD" |      | "HELLO, WORLD" |
 * +--------+----------------+      +----------------+
 * 长度域被截掉需要指定参数initialBytesToStrip表示拿到一个完整的数据包向业务解码器传递之前跳过多少字节
 * 创建LengthFieldBasedFrameDecoder:new LengthFieldBasedFrameDecoder(Integer.MAX, 0, 4, 0, 4);第五个参数是initialBytesToStrip,这里为4表示获取完一个完整的数据包忽略前面的四个字节,应用解码器拿到的是不带长度域的数据包
 * 3.基于偏移长度的拆包
 * BEFORE DECODE (17 bytes)                      AFTER DECODE (17 bytes)
 * +----------+----------+----------------+      +----------+----------+----------------+
 * | Header 1 |  Length  | Actual Content |----->| Header 1 |  Length  | Actual Content |
 * |  0xCAFE  | 0x00000C | "HELLO, WORLD" |      |  0xCAFE  | 0x00000C | "HELLO, WORLD" |
 * +----------+----------+----------------+      +----------+----------+----------------+
 * 前面几个固定字节表示协议头通常包含magicNumber,protocol version 之类的meta信息,紧跟着后面的是一个长度域,表示包体有多少字节的数据
 * 创建LengthFieldBasedFrameDecoder:new LengthFieldBasedFrameDecoder(Integer.MAX, 4, 4);lengthFieldOffset是4表示跳过4个字节后面的是长度域
 * 4.基于可调整长度的拆包
 * BEFORE DECODE (17 bytes)                      AFTER DECODE (17 bytes)
 * +----------+----------+----------------+      +----------+----------+----------------+
 * |  Length  | Header 1 | Actual Content |----->|  Length  | Header 1 | Actual Content |
 * | 0x00000C |  0xCAFE  | "HELLO, WORLD" |      | 0x00000C |  0xCAFE  | "HELLO, WORLD" |
 * +----------+----------+----------------+      +----------+----------+----------------+
 * 创建LengthFieldBasedFrameDecoder:new LengthFieldBasedFrameDecoder(Integer.MAX, 0, 3, 2, 0);长度域在数据包最前面表示无偏移即lengthFieldOffset为 0,长度域的长度为3即lengthFieldLength为3,长度域表示的包体的长度略过header,lengthAdjustment表示包体长度调整的大小,长度域的数值表示的长度加上修正值表示的是带header的包即lengthAdjustment为2
 * 5.基于偏移可调整长度的截断拆包
 * BEFORE DECODE (16 bytes)                       AFTER DECODE (13 bytes)
 * +------+--------+------+----------------+      +------+----------------+
 * | HDR1 | Length | HDR2 | Actual Content |----->| HDR2 | Actual Content |
 * | 0xCA | 0x000C | 0xFE | "HELLO, WORLD" |      | 0xFE | "HELLO, WORLD" |
 * +------+--------+------+----------------+      +------+----------------+
 * 拆完数据包HDR1和长度域丢弃剩下第二个header和有效包体,一般HDR1即magicNumber表示应用只接受以该magicNumber开头的二进制数据,RPC里面用的比较多
 * 创建LengthFieldBasedFrameDecoder:new LengthFieldBasedFrameDecoder(Integer.MAX, 1, 2, 1, 3);长度域偏移为1即 lengthFieldOffset为1,长度域长度为2即lengthFieldLength为2,长度域表示的包体的长度略过HDR2,拆包的时候HDR2当作是包体的的一部分来拆,HDR2的长度为1即 lengthAdjustment为1,拆完数据包截掉前面三个字节即initialBytesToStrip为 3
 * 6.基于偏移可调整变异长度的截断拆包
 * BEFORE DECODE (16 bytes)                       AFTER DECODE (13 bytes)
 * +------+--------+------+----------------+      +------+----------------+
 * | HDR1 | Length | HDR2 | Actual Content |----->| HDR2 | Actual Content |
 * | 0xCA | 0x0010 | 0xFE | "HELLO, WORLD" |      | 0xFE | "HELLO, WORLD" |
 * +------+--------+------+----------------+      +------+----------------+
 * 长度域字段的值为16,其字段长度为2,HDR1的长度为1,HDR2的长度为1,包体的长度为12,需要设置长度域后面再跟多少字节就可以形成一个完整的数据包,长度域的值为16减掉3才是真是的拆包所需要的长度即lengthAdjustment为-3
 * LengthFieldBasedFrameDecoder源码剖析:
 * LengthFieldBasedFrameDecoder的构造函数byteOrder表示字节流表示的数据是大端还是小端用于长度域的读取,lengthFieldEndOffset表示紧跟长度域字段后面的第一个字节的在整个数据包中的偏移量,failFast如果为true表示读取到长度域的值超过maxFrameLength抛TooLongFrameException,而为false表示只有当真正读取完长度域的值表示的字节之后抛TooLongFrameException,默认情况设置为true,建议不要修改否则可能会造成内存溢出
 * LengthFieldBasedFrameDecoder调用decode()方法实现拆包抽象:
 * 1.获取frame长度:(1)获取需要待拆包的包大小:如果当前可读字节还未达到长度长度域的偏移说明读不到长度域的忽略不读,计算长度域的实际字节偏移,调用getUnadjustedFrameLength()方法获取实际的未调整过的包长度,如果拿到的长度为负数跳过长度域并抛出异常,调整包的长度后面统一做拆分,(2)长度校验:整个数据包的长度还没有长度域长抛异常,数据包长度超出最大包长度进入丢弃模式,当前可读字节已达到frameLength跳过frameLength个字节,丢弃之后后面有可能就是一个合法的数据包,当前可读字节未达到frameLength说明后面未读到的字节也需要丢弃进入丢弃模式,先把当前累积的字节全部丢弃,bytesToDiscard表示还需要丢弃多少字节,调用failIfNecessary()方法判断是否需要抛异常,不需要再丢弃后面的未读字节重置丢弃状态,如果没有设置快速失败或者设置快速失败并且是第一次检测到大包错误抛TooLongFrameException异常让handler去处理,如果设置快速失败并且是第一次检测到打包错误抛TooLongFrameException异常让handler去处理,failFast默认为true,firstDetectionOfTooLongFrame为true,第一次检测到大包抛异常
 * 2.丢弃模式的处理:如果当前处在丢弃模式,计算需要丢弃多少字节,取当前还需可丢弃字节和可读字节的最小值,丢弃掉之后调用 failIfNecessary()方法默认情况不会继续抛异常,如果设置 failFast为false等丢弃完之后才抛异常
 * 3.跳过指定字节长度:验证当前是否已经读到足够的字节,如果读到字节在下一步抽取一个完整的数据包之前,需要根据initialBytesToStrip设置来跳过某些字节,跳过的字节不能大于数据包的长度,否则就抛CorruptedFrameException异常
 * 4.抽取frame:拿到当前累积数据的读指针,获取待抽取数据包的实际长度,调用ByteBuf的retainedSlice()[无内存拷贝开销]方法抽取数据包并且移动读指针
 * 长度域拆包器LengthFieldBasedFrameDecoder的拆包包括合法参数校验,异常包处理,以及最后调用ByteBuf的retainedSlice()来实现无内存copy的拆包
 */
public final class SimpleServer {

    public static void main(String[] args) throws Exception {
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();

        try {
            ServerBootstrap serverBootstrap = new ServerBootstrap();
            serverBootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .handler(new SimpleServerHandler())
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        public void initChannel(SocketChannel ch) throws Exception {
                        }
                    });

            ChannelFuture channelFuture = serverBootstrap.bind(8888).sync();

            channelFuture.channel().closeFuture().sync();
        } finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }

    private static class SimpleServerHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            System.out.println("channelActive");
        }

        @Override
        public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
            System.out.println("channelRegistered");
        }

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
            System.out.println("handlerAdded");
        }
    }
}