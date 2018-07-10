package com.netty.flash.ex13;

import sun.nio.ch.DirectBuffer;

import java.nio.ByteBuffer;

/**
 * Netty课程总结
 * 客户端                  服务端
 *       |       2.新连接       |      1.监听端口
 *       | <------------------> |     NioEventLoop
 *       |       Channel         |
 *       |      3.接收数据     |     4.业务逻辑        ---
 *       | --------------------> |      ChannelHandler   |
 *       |       ByteBuf          |              ... Pipeline    |.
 *       |      5.发送数据     |     4.业务逻辑        ---
 *       | <-------------------- |      ChannelHandler
 *       |       ByteBuf          |
 * 1.监听端口:启动线程监听端口,对应Boss的NioEventLoop,2.新连接:不断检测是否有新连接,Boss线程轮询到新连接给Channel创建Pipeline即ChannelHandler业务逻辑处理链,3.接收数据,4.业务逻辑ChannelHandler:接收到数据通过一系列ChannelHandler处理链进行拆包拆分成自定义协议数据包进行业务逻辑处理编码写到客户端,5.发送数据
 * Netty组件:NioEventLoop/Channel/ByteBuf/Pipeline/ChannelHandler
 * Netty实现机制:Netty里面有两大线程:Boss线程和两倍CPU个Worker线程,线程主要做三件事情:1.select:轮询注册到线程绑定的Selector上事件,Boss线程select新连接事件/Workder线程select读事件,2.process selected keys:通过Pipeline轮询到事件获取Netty自定义Channel对应的Pipeline,服务端Pipeline有head节点、acceptor处理器、tail节点,Boss线程轮询到新连接进来通过acceptor节点通过Chooser选择Worker线程将新连接封装成Channel扔到Worker线程组,选择出Worker线程调用异步方法把绑定操作封装成Task扔到Worker线程,Worker线程处理数据读写获取事件绑定的Attachment对应的Channel,把事件通过Pipeline往前传播经过每个处理器,每次跟数据读写相关的底层操作都是在head节点上处理的,head节点Unsafe处理底层数据ByteBuf读写,3.run tasks:Worker线程处理的异步任务包括Boss线程扔过来的绑定Task把新连接绑定到Worker线程并且新连接底层对于的Channel注册到Worker线程对应的Selector上面,外部线程调用writeAndFlush()一系列方法判断是在当前Reactor线程调用直接立马执行,外部线程调用放到Task Queue里面执行
 */

/**
 * 单独开一个handler线程池调用ctx.writeAndFlush(),最终会调用到AbstractChannelHandlerContext的write()方法, netty判断调用这段代码的线程非reactor线程把writeAndFlush操作封装成task丢到管理这条channel的reactor线程[NioEventLoop]的队列里面(mpscQueue)异步执行,丢到队列里面之后立即返回即返回到ctx.writeAndFlush()下面的代码,因此极有可能是下面的代码已经执行完毕,扔到netty reactor线程中队列的writeAndFlushwriteAndFlush任务还没执行完毕
 * private void write(Object msg, boolean flush, ChannelPromise promise) {
 *      AbstractChannelHandlerContext next = findContextOutbound();
 *      final Object m = pipeline.touch(msg, next);
 *      EventExecutor executor = next.executor();
 *      if (executor.inEventLoop()) {//false,表明不在netty的reactor线程中执行
 *          if (flush) {
 *              next.invokeWriteAndFlush(m, promise);
 *          } else {
 *              next.invokeWrite(m, promise);
 *          }
 *      } else {
 *          AbstractWriteTask task;
 *          if (flush) {
 *              task = WriteAndFlushTask.newInstance(next, m, promise);//封装成一个Task
 *          }  else {
 *              task = WriteTask.newInstance(next, m, promise);
 *          }
 *          safeExecute(executor, task, promise, m);//丢到reactor的队列中异步执行
 *     }
 *  }
 * 解决此问题的方法:
 * 1.使用回调
 * ctx.writeAndFlush(msg, new GenericFutureListener() {
 *  @Override
 *  public void operationComplete(Future future) throws Exception {
 *  if (future.isSuccess()) {
 *      // 剩下的逻辑
 *      }
 *    }
 * });
 * 2.将 writeAndFlush和剩下的逻辑统一封装成task,丢到netty的reactor线程池里面处理
 * ctx.executor().execute(new Runnable() {
 *  @Override
 *  public void run() {
 *      ctx.writeAndFlush();
 *      // 剩下的逻辑
 *    }
 * })
 * <p>
 * 一个客户端对应一个channel,然后这些channel注册到selector上,acceptor是一个单线程,在这个线程里selector去轮询channel是否准备好即哪些已经accepted,然后如果准备好了的话就是通过reactor线程池里去拿线程来处理准备好的channel,那如果reactor线程池里的线程用完了呢 是有一个队列吗,channel是怎么切换的?
 * Netty里面,不管boos线程还是worker线程,他们所做的事框架都是一样的
 * while(true) {
 *  1.轮询事件
 *  2.处理事件
 *  3.执行异步任务
 * }
 * 对于boos线程[一般为单线程],常见模式如下
 * while(true) {
 *  1.轮询出n个accept事件
 *  2. for(1~n) { 通过ServerBootstrapAcceptor来将该连接扔到worker线程池[NioEventLoopGroup]里去注册 }
 *  3.忽略
 * }
 * 对于worker线程[一般为多线程],常见模式如下
 * while(true) {
 *  1.轮询出m个channel的read事件
 *  2. for(1~m) {逐个传播 channelRead(Type object) 事件,其中首次传播的object类型为ByteBuf}
 *  3. 执行外部线程调用ctx.writeAndFlush()类任务,传播 writeAndFlush事件 or 注册新连接的OP_READ
 * }
 * 因此,不管boos还是worker,都使用NioEventLoop来控制逻辑,可以看作是设计模式里面的模板方法模式
 * 结合上面的前提,下面是结论
 * 1.accept操作只在boos线程里面去做,甚至可以批量accept
 * 2.boos线程accept出Channel之后,直接在NioEventLoopGroup(worker线程池)里面选择一个NioEventLoop(worker线程),将该连接扔到此NioEventLoop对应的selector去注册, 扔的姿势是:由于是boos扔的,那么对于wroker来说就是外部线程,所以这个过程会封装成一个task,在worker的第三个步骤执行,然后boos扔的顺序是Round Robin(wroker1,worker2,3,4,1,2,3,4...),后续这条连接所有的读写都由这个NioEventLoop,也就是reactor线程管理
 * 3.一条reactor线程管理多个Channel串行执行所有事件,因此一个Channel如果有耗时的操作,会影响其他Channel的执行
 * <p>
 * 都说netty解决jdk的epoll的空轮询bug,但是为什么在netty的代码里面fix空轮询bug的逻辑是在NioEventLoop里而不是在EpollEventLoop?毕竟NioEventLoop里面使用的是select啊,select又和epoll啥关系?
 * NioEventLoop里面虽然使用了selector,并且去轮询io事件的时候调用selector.select(),但是其实底层jvm是使用epoll来实现的selector的逻辑,EpollEventLoop直接用c实现了epoll,没有fix 空轮询bug的逻辑,调了调参数,让性能更高点,nio相关的除了epoll,linux上还有select和poll, select和poll差不多,区别就是api不太一样,还有就是select有1024的限制
 * <p>
 * netty长连集群环境下,如何使用实现单机无缝重启?如果直接暴力重启,会发生什么?
 * 1.正在三次握手的连接建立失败,客户端会抛出异常,体验很不友好
 * 2.客户端连接建立成功,刚把数据发出去,这时候服务器咔,关了,客户端会很懵逼,不知所措啊
 * 3.服务端本身处理比较慢,还在吭哧吭哧一个劲的在计算响应,暴力重启过程中,服务端吐不了响应了,也表示很无奈,客户端更是啥响应也收不到
 * 解决方案:
 * 1.首先关闭端口监听：这样长连上层的四层负载均衡器发现端口不通,会摘掉本机,新建连接都会落到其他机器上
 * 2.向所有客户端发送断开重连指令： 通过自定义协议,服务端告诉客户端,我快不行了,你准备断开重连吧,客户端收到指令,可以直接重连
 * 3.等待一个服务端最大处理时间 客户端说,不行,我请求已经给你发出去了,你在挂之前给我处理完,我保证这个请求处理完我就不给你发请求了,于是服务端会在最大处理时间范围内给客户端吐出响应
 * 4.重启 服务端和客户端都很开心,客户端收到了响应,乖乖断开了,服务端也没有新的连接,老的请求也处理完了,安心重启
 * 整个过程,对业务来说,不会有丝毫抖动
 * 最后,我们再来看下,对应netty里面的实现
 * // 关闭断口监听,不接受新的连接
 * boosGroup.shutdownGracefully().syncUninterruptibly();
 * // 告诉客户端,你准备断开重连吧
 * for(channel: channels) {
 *  channel.writeAndFlush(reconnectDataPacket);
 * }
 * // 把残留的请求处理掉
 * sleep(maxProcessTime);
 * // 关闭客户端连接
 * workerGroup.shutdownGracefully().syncUninterruptibly();
 *<p>
 * 理清netty内存管理器的继承关系:
 * 1.ByteBufAllocator接口具有分配堆内和堆外内存的功能,具有一系列外观接口,给用户多个选择
 * 2.AbstractByteBufAllocator是ByteBufAllocator的抽象实现,实现了大部分外观接口,但是会暴露newDirectBuffer(min,max)和newHeapBuffer(min,max)给子类实现
 * 3.AbstractByteBufAllocator的子类只有两个,主要就是实现上述两个抽象方法
 *  3.1一个是UnpooledByteBufAllocator,具有分配非池化的堆外和堆内内存
 *  3.2另外一个是PooledByteBufAllocator,具有分配池化的堆外和堆内内存
 * <p>
 * BIO和NIO区别通俗解释
 * 1.BIO:每一条连接都需要一个线程死循环去检查是否有数据可读,一万个连接就需要1万个线程,大多数情况下,很多线程都轮不出啥数据可读,效率低下,非常消耗系统资源,敬而远之
 * 2.NIO:只需一个线程,这个线程管理一个轮询器,来多少连接都绑定在此轮询器上,然后这一个线程就搞个死循环,不断从轮询器上轮出有数据可以读的连接,每次都更有可能轮出可读的连接(毕竟我一次性可以轮10000次),批量处理
 * <p>
 * 面向用户的在线长连集群,如果要做到连接数负载均衡,接入层负载均衡器是应该采用round robin策略还是连接数最小策略,为什么?
 * 1.使用连接数优先策略,那么重启的时候,由于重启的这台机器连接数最少,流量瞬间打到这台机器上,可能直接打崩掉,我们长链集群之前按连接数优先策略配置,每次要测试新功能是否ok,一般都会在一台机器上测试,这台机器重启之后,直接被打到高潮(old gc)
 * 2.使用round robin策略,表面上看可能这台机器的连接数永远追不上其他机器,由于是面向用户端的长链集群,一条长连接的生命周期是有限的,假设生命周期最多1个小时,重启的那一刻,虽然这台机器连接数为0,其他机器有可能有十几万,但是一个小时之后,这十几万的连接数全部归0,而一个小时之内,这台重启后的机器新增的连接数和集群内其他机器一样,最终,一个小时之后达到了平衡
 * 所以使用round robin策略的好处总结为两点:1.新上线的机器可以平滑切入流量,不至于一下被打挂掉;2.一段时间之后,达到连接数平衡,满足连接数均衡需求
 * <p>
 * netty内存管理
 * (1)内存分类：
 * 1.按照内存类型可分为堆内和堆外,区别是堆内走gc,堆外不走
 * 2.按照分配策略可分为非池化和池化,非池化每次都会重新申请,池化的话指的是开辟一块大的内存区,需要的时候来取,不需要的时候归还
 * (2)池化内存(这里以堆外内存举例,堆内内存区别不大)：
 * 1.默认情况下,netty会开2*cpu个reactor线程,每个线程各自管理一大块内存,即DirectArena,另外对于某些固定大小的ByteBuf,使用完了并不是直接回收,而是有可能丢到每个线程的缓存空间(MemoryRegionCache)中,这些缓存空间和DirectArena组合成了PoolThreadCache进行管理
 * 2.这一大块内存(DirectArena)里面包含多个16M的内存块,16M的内存块,即PoolChunk,netty也会实时统计每个16M内存块的使用情况,每次分配内存的时候按照一定策略选择某个16M内存块进行分配
 * 3.16M作为内存管理显然有点太大,因此,16M的内存块又被重新分为2048个小的内存页,每个内存页大小为8K,以一个完全二叉树来记录内存分配情况,为什么要使用完全二叉树?是因为完全二叉树可以很好的标识连续的内存块的分配情况,比如0~8K,8K~16K或者0~16K,16K~32K的区段是否已经分配
 * 4.对有些应用来说,每次可能只需要分配几个字节,显然,每次分配8K,有点大材小用,因此,对于8K的page,netty继续进行划分为更小的内存片,每个page以PoolSubpage来管理这些子page,使用位图来标识子page的使用情况
 * 5.netty的池化的内存分配和释放：
 *  5.0 内存大小如果大于16M,直接申请内存,走非池化逻辑
 *  5.1 如果当前线程的缓存空间(MemoryRegionCache)中有缓存的PooledByteBuf,并且申请的内存大小规格与几种固定的规格匹配的话就直接拿出来复用,这些个规格分类的PooledByteBuf大小上限为32K,即大于32K不走缓存
 *  5.2 否则的话,每次分配内存的时候,在当前线程管理的内存块中,选择一个16M的内存块,然后在这个内存块划一个最合适大小的区间来进行分配,分配完之后标记上已分配,使用二叉树和位图技术实现
 *  5.3 用完释放的时候,如果释放的这个PooledByteBuf大小与缓存空间中的某个内存规格相匹配,并且这个规格的缓存容量未满(FixedMpscQueue),那么直接丢进去,否则的话,需要标记对应的16M内存的这段连续的内存空间已释放(二叉树和位图技术)
 * 6.另外,因为每次分配内存,都会创建PooledByteBuf对象,因此,netty还维持了一个对象池,先去对象池里面去找,用完之后的PooledByteBuf也会被丢回对象池进行复用
 * <p>
 * 支持高并发大规模消息推送系统的分层设计:
 * ------------                                 --------------                                ------------                                 -------------
 * |             |              tcp              |               |             rpc              |            |             rpc               |              |
 * |             |<-------------------------| Connect |<-------------------------| Server |<-------------------------| Business |
 * |             |------------------------->|               |------------------------->|            |------------------------->|              |
 * |             |           token              |              | connectIp,connectId  |           |                                 |              |
 * ------------             -                   --------------                                 -----------                                 -------------
 *                                                                                                      |    |
 *                                                                                                  -----------
 *                                                                                                  |    db    | token(connectIp,connectId)
 *                                                                                                  -----------
 * 1.对业务暴露客户端api和服务端api,通过服务端api可以推送消息到客户端,客户端api接收消息
 * 2.接入层connect负责维持海量用户长连,只负责透传数据,不做业务逻辑,不做存储
 * 3.业务逻辑层server负责具体协议的解析,存储在线用户状态,负责后端业务的接入,支持各类业务功能的消息推送,比如群发,单发等
 * 推送消息的流程:
 * 1.首先用户通过连接到connect某台具体的connect机器(这里省去了路由相关的逻辑)
 * 2.客户端发送一个登录指令,connect透传,透传的时候带上connect的ip,生成一个这条连接的唯一id和指令数据打包发送到server
 * 3.server拿到这个数据,从登录指令中解析到用户唯一标识token,把token和connectIp以及connectId存到db(一般使用redis等实现)
 * 4.业务后端推送消息的时候,就可以通过token来推送
 * 5.server接收到推送请求,首先从db里面找到connectIp和connectId,然后通过rpc路由通过指定的一台connect将消息推送出去
 * 为什么长连接入层和业务逻辑层分离?
 * 1.长连接入层是非常重要,非常关键的一层,不适合做频繁改动,做成无状态,功能单一的层水平扩容非常方便,保证接入层的稳定性
 * 2.业务逻辑层变更频繁,如果将用户接入层和业务逻辑层合并,那么每一次业务接入层的改动都会影响到客户端的长连接,分开之后每次业务逻辑上线长连接都无感知
 * <p>
 * nio线程做的事情就三个步骤:
 * 1.select出io事件
 * 2.处理io事件,主要是从channel中读数据（worker）,读新连接(boos)
 * 3.执行task
 * 最外层一个死循环,不断重复这个步骤,第一个过程和第二个过程理论上花费的时间比较少,所以很快就进入到第三个过程
 * 另外,如果在nio线程内执行任务,是立马执行的,只有在外部线程执行任务的时候才会丢到nio线程的task queue里面,nio线程在死循环里可以执行到
 * <p>
 * Netty是一个异步事件驱动框架:怎么理解?
 * 1.异步怎么理解:外部线程执行write或者execute任务的时候会立马返回:为什么会立马返回:是因为他内部有一个牛逼哄哄的mpsc(多生产者单消费者)队列:所有外部线程的任务都给扔到这个队列里:同时把回调:也就是future绑定在这个任务上:reactor线程会在第三步挨个执行这些任务:执行完之后callback:典型的观察者模式[观察者:GenericFutureListener,被观察者:ChannelFuture[ChannelPromise],注册观察者[向被观察者添加观察者]:addListener0(),通知观察者:[invokeWrite0()->safeSetFailure()->setFailure()/invokeFlush0()->safeSuccess()->trySuccess()]->notifyListener0()]
 * 2.事件怎么理解:netty内部默认两倍cpu核数个reactor线程:每个reactor线程维护一个selector:每个selector维护一堆channel:每个channel都把读写等事件都注册到selector上
 * 3.驱动怎么理解:netty里面默认所有的操作都在reactor线程中执行:reactor线程核心的就是run方法:所有的操作都由这个方法来驱动:驱动的过程分为三个过程:轮询事件:处理事件:处理异步任务
 * <p>
 * 堆外内存是指创建java.nio.DirectByteBuffer通过Unsafe接口直接通过os::malloc来分配内存, 然后将内存的起始地址和大小存到java.nio.DirectByteBuffer对象里,主动调用System.gc()开启DisableExplicitGC参数通过触发一次GC操作来回收堆外内存, DirectByteBuffer对象创建关联PhantomReference:PhantomReference用来跟踪对象何时被回收,GC过程中如果发现某个对象除了只有PhantomReference引用它之外并没有其他的地方引用它将会把这个引用放到java.lang.ref.Reference.pending队列里:在GC完毕的时候通知ReferenceHandler这个守护线程去执行后置处理:DirectByteBuffer关联的PhantomReference是PhantomReference的一个子类:在最终的处理里通过Unsafe的free接口来释放DirectByteBuffer对应的堆外内存块
 * 然而按照"谁allocate申请内存分配谁free释放内存"套路来说Netty本身就支持释放堆外内存:ByteBuffer.allocateDirect分配DirectByteBuffer的时候创建继承PhantomReference的Cleaner即cleaner = Cleaner.create(this, new Deallocator(base, size, cap));使用完DirectByteBuffer通过获取DirectByteBuffer的cleaner成员变量调用其clean()方法会执行Deallocator任务调用Unsafe的freeMemory(address)释放堆外内存,此时可以不需要按照调用System.gc()开启DisableExplicitGC参数触发GC操作来实现回收堆外内存
 */
public class Scratch {

    private static final int _100M = 100 * 1024 * 1024;

    public static void main(String[] args) throws Exception {
        ByteBuffer byteBuffer = ByteBuffer.allocateDirect(_100M);
        System.out.println("start");

        System.in.read();
        sleep(10000);

        // System.gc();
        clean(byteBuffer);

        System.out.println("end");
        System.in.read();
    }

    private static void sleep(int time) throws Exception {
        Thread.sleep(time);
    }


    public static void clean(final ByteBuffer byteBuffer) {
        if (byteBuffer.isDirect()) {
            ((DirectBuffer) byteBuffer).cleaner().clean();
        }
    }
}