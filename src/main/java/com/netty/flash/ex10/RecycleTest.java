package com.netty.flash.ex10;

import io.netty.util.Recycler;

/**
 * Netty两大性能优化工具类:
 * 2.Recycler:实现轻量级对象池机制,对象池的作用一方面能达到快速创建对象的能力,另一方面避免反复创建对象,减少JVM Young GC的压力,Netty通过Recycler方式获取ByteBuf对象原因是ByteBuf对象创建在Netty里面非常频繁,并且一个ByteBuf对象大小比较占空间,解决创建对象频繁场景
 * 轻量级对象池Recycler:创建对象的时候不需要每次都通过new方式创建,如果Recycler有对象直接获取二次利用,不需要对象的时候放入Recycler对象池
 * Recycler的使用:
 * (1)定义基于FastThreadLocal的轻量级对象池Recycler负责对象的回收和二次利用,不需要每次分配内存减少内存使用,减少new对象频率即减少Young GC频率
 * (2)创建对象通过Recycler的get()获取对象池里面的对象,不需要对象显式调用recycle()方法回收对象放到对象池
 * (3)通过自定义newObject()方法定义对象池里面无对象场景对象创建,参数handle负责调用recycle()方法对象回收到对象池Recycler
 * Recycler的创建:
 * Recycler成员变量FastThreadLocal<Stack<T>>类型的threadLocal,每个线程维护Stack对象,Stack成员变量包括DefaultHandle类型数组elements[实际存放对象池,Handle包装对象并且被外部对象引用从而回收对象],thread[当前Stack归属线程],ratioMask[控制对象回收频率],maxCapacity[承载元素最大容量],maxDelayedQueues[当前线程创建的对象能够释放的最大线程数量],head/prev/cursor,availableSharedCapacity[当前线程创建的对象在其他线程缓存的上限/最大数量]
 * Recycler默认值:maxCapacityPerThread默认值为DEFAULT_INITIAL_MAX_CAPACITY_PER_THREAD[32768]即DefaultHandle类型数组elements最大容量为32768,maxSharedCapacityFactor默认值为MAX_SHARED_CAPACITY_FACTOR[2],ratio默认值为RATIO[8]->radioMask默认值为safeFindNextPositivePowerOfTwo(ratio) - 1[7],maxDelayedQueuesPerThread默认值为MAX_DELAYED_QUEUES_PER_THREAD[Runtime.getRuntime().availableProcessors()*2]
 * Stack默认值:maxCapacity默认值为DEFAULT_INITIAL_MAX_CAPACITY_PER_THREAD[32768],availableSharedCapacity默认值为max(maxCapacity[32768] / maxSharedCapacityFactor[2], LINK_CAPACITY[16]),DefaultHandle类型数组elements默认最大容量为32768,radioMask默认值为safeFindNextPositivePowerOfTwo(ratio) - 1[7],maxDelayedQueues默认值为MAX_DELAYED_QUEUES_PER_THREAD[Runtime.getRuntime().availableProcessors()*2]
 * 从Recycler获取对象:
 * 1.获取当前线程的Stack->threadLocal.get()
 * 2.从Stack里面弹出DefaultHandle对象[Recycler回收已创建的对象触发]->stack.pop()
 * 调用Stack的pop()方法弹出DefaultHandle对象,判断当前Stack对象数量是否等于0,等于0调用scavenge()方法判断当前Stack之前创建的对象是否跑去其他线程释放并且从其他线程捞回对象,获取elements数组当前Stack对象数量索引位置的DefaultHandle对象
 * 3.创建DefaultHandle对象并绑定到Stack[首次调用Recycler的get()方法触发]->stack.newHandle()
 * 弹出DefaultHandle对象为空调用Stack的newHandle()方法创建DefaultHandle对象绑定到当前Stack对象,调用用户自定义newObject()方法创建对象设置DefaultHandle的成员变量value返回
 * 回收对象到Recycler:
 * 调用Handle的recycle()方法回收对象,使用Stack的push()方法把当前对象压入栈里面,判断当前线程是否为创建Stack的thread执行同/异线程回收对象
 * 1.同线程回收对象->stack.pushNow()
 * 设置recycleId/lastRecycledId为OWN_THREAD_ID[ID_GENERATOR.getAndIncrement()],当前Stack对象数量超过承载元素最大容量或者Handle没有被回收过并且当前回收对象数量+1&对象回收频率不等于0即回收1/8对象则丢弃对象,当前Stack对象数量达到elements数组容量则重新创建2倍Stack对象数量的数组,赋值elements数组当前Stack对象数量索引位置为回收DefaultHandle对象
 * 2.异线程回收对象->stack.pushLater()[WeakOrderQueue存放其他线程创建对象]
 * (1)获取WeakOrderQueue->DELAYED_RECYCLED.get()
 * 获取DELAYED_RECYCLED[FastThreadLocal<Map<Stack<?>, WeakOrderQueue>>类型]的ThreadLocalMap delayedRecycled,通过当前Stack对象获取delayedRecycled的WeakOrderQueue,当前queue为空即从未回收过对象判断回收对象线程数量是否超过回收最大线程数量,超过最大线程数量delayedRecycled存放当前Stack对象和对应的WeakOrderQueue.DUMMY
 * (2)创建WeakOrderQueue->WeakOrderQueue.allocate()
 * 通过WeakOrderQueue的allocate()方法给当前线程分配当前Stack对象对应的WeakOrderQueue,使用reserveSpace()方法判断当前Stack对象能否分配LINK_CAPACITY[16]个内存即Stack允许外部线程缓存对象数量是否超过16,通过CAS操作设置availableSharedCapacity值为available - space,当前Stack对象有足够容量分配LINK_CAPACITY个对象创建WeakOrderQueue
 * WeakOrderQueue结构:Handle放到Link只需要判断当前Link有无空的Handle实现批量分配当前Stack对象WeakOrderQueue
 * --------------------------------------
 * | Link-->Link-->Link-->Link |
 * |    |                                  |    |-------->
 * | head                            tail   |  next
 * --------------------------------------
 * Handle Handle ... Handle
 *                  |
 *           readIndex
 * 构造WeakOrderQueue创建Link将head/tail指向Link,设置owner为当前线程弱引用,把分配的WeakOrderQueue插入到Stack对象头部实现通过链表方式把Stack跟其他外部释放的线程WeakOrderQueue绑定
 * WeakOrderQueue与Stack绑定:
 * ------------
 * | Handle |               --------------------------
 * |     ...      |---------> | WeakOrderQueue | Thread3
 * | Handle |   head     --------------------------
 * ------------                               |
 * Thread1                 --------------------------
 *                               | WeakOrderQueue | Thread2
 *                               --------------------------
 * (3)将对象追加到WeakOrderQueue->queue.add()
 * 通过WeakOrderQueue的add()方法设置DefaultHandle对象的lastRecycledId为WeakOrderQueue的id,获取尾指针tail判断tail Link长度是否等于LINK_CAPACITY并且能否允许再分配Link,允许分配Link创建Link并且赋值给尾指针tail设置写指针writeIndex为tail Link长度,反之则丢弃DefaultHandle对象,将DefaultHandle对象追加到尾指针tail的elements数组,把DefaultHandle对象的stack设置成空表示DefaultHandle对象已经不属于stack,将尾指针tail的写指针writeIndex+1
 * 异线程收割对象->stack.scavenge()从其他线程回收对象
 * (1)使用scavengeSome()方法获取当前需要回收WeakOrderQueue cursor,判断cursor是否为空赋值为头节点head,当前头节点head为空表示当前Stack无相关联的WeakOrderQueue,while循环向当前Stack关联的WeakOrderQueue回收对象
 * (2)使用WeakOrderQueue cursor的transfer()方法把WeakOrderQueue的Link传输到Stack,获取当前Stack对象的head节点判断head节点的readIndex是否等于LINK_CAPACITY即Link的大小,相等则head节点指向head节点的next节点,获取head节点的readIndex[表示从readIndex索引开始取对象]和srcEnd[当前Link的大小即Link对象数量],当前Link传输到Stack的对象数量+当前Stack的大小计算期望容量,期望容量超过当前Stack的elements数组长度通过赋值srcEnd为readIndex+允许传输对象数量进行扩容,
 * 获取当前Link数组elements和当前Stack数组elements,for循环将当前Link数组元素传输到当前Stack底层数组,Link数组元素element的recycleId等于0表示对象未被回收过赋值为lastRecycledId,将Link数组元素置为空并且把当前Stack数组elements元素赋值为element,当前Link回收完并且后面还有Link调用reclaimSpace()方法将availableSharedCapacity调整为LINK_CAPACITY,将head节点的读指针readIndex指向srcEnd并且更新当前Stack的大小
 * (3)获取WeakOrderQueue cursor的next节点WeakOrderQueue,判断cursor的关联线程owner是否为空,关联线程为空判断cursor是否有数据把WeakOrderQueue的Link传输到Stack并且释放cursor节点
 * (4)如果没有回收到对象则重置prev指针置为空/cursor指针置为head头节点即下次从头部开始回收
 * --------------------------------------
 * | Link-->Link-->Link-->Link |
 * |    |                                  |    |-------->
 * | head                            tail   |  next
 * --------------------------------------
 * Handle Handle ... Handle
 *                  |                |
 *           readIndex    srcEnd
 * 从对象池Recycler获取对象首先通过ThreadLocal方式获取当前线程的Stack,当前Stack有对象直接从Stack弹出DefaultHandle对象,Stack无对象从当前Stack关联的其他线程WeakOrderQueue回收对象,回收成功当前Stack有对象直接弹出,回收不到对象则创建对象和当前Stack关联,关联后当前线程回收对象压入Stack,其他线程回收对象放到其他线程与Stack关联的WeakOrderQueue
 */
public class RecycleTest {
    private static final Recycler<User> RECYCLER = new Recycler<User>() {
        @Override
        protected User newObject(Handle<User> handle) {
            return new User(handle);
        }
    };

    private static class User {
        private final Recycler.Handle<User> handle;

        public User(Recycler.Handle<User> handle) {
            this.handle = handle;
        }

        public void recycle() {
            handle.recycle(this);
        }
    }

    public static void main(String[] args) {
        User user0 = RECYCLER.get();

        user0.recycle();

        User user1 = RECYCLER.get();

        System.out.println(user1 == user0);
    }
}