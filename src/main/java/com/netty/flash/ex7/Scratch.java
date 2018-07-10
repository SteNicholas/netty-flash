package com.netty.flash.ex7;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;

/**
 * ByteBuf:内存分配负责把数据从底层IO读到ByteBuf传递应用程序,应用程序处理完之后再把数据封装成ByteBuf写回到IO,ByteBuf是直接与底层IO打交道的抽象
 * 1.内存与内存管理器的抽象
 * ByteBuf结构以及重要API
 * (1)ByteBuf结构
 *  +-------------------+-----------------+----------------+-------------+
 * |discardable bytes|readable bytes|writable bytes|resize bytes|
 * |                           | (CONTENT) |   (max writable bytes)     |
 *  +-------------------+-----------------+----------------+-------------+
 * |                          |                       |                     |                 |
 * 0     <=    readerIndex <=  writerIndex <= capacity <= maxCapacity
 *                [读数据指针]     [写数据指针]
 * (2)read,write,set方法
 * read方法读数据在当前readerIndex指针开始往后读,readByte()/readShort()/readInt()/readLong()把readerIndex指针往前移一格,读readerIndex指针后面1/2/4/8个字节数据,write方法把数据写到ByteBuf,read/write方法readerIndex/writerIndex指针往后移动,set方法在当前指针设置成指定值,不会移动指针
 * (3)mark,reset方法
 * mark方法标记当前readerIndex/writerIndex指针,reset方法还原之前读/写数据readerIndex/writerIndex指针,不会改变readerIndex/writerIndex指针确保指针读/写完数据能够保持原样
 * readableBytes():this.writerIndex - this.readerIndex,writableBytes():this.capacity - this.writerIndex,maxWritableBytes():this.maxCapacity - this.writerIndex
 * <p>
 * ByteBuf分类
 * AbstractByteBuf抽象实现ByteBuf即ByteBuf骨架实现保存readerIndex/writerIndex/markedReaderIndex/markedWriterIndex/maxCapacity[记录ByteBuf最多分配容量],_get***()/_set***()抽象方法子类实现
 * (1)Pooled和Unpooled:Pooled池化内存分配每次都是从预先分配好的一块内存取一段连续内存封装成ByteBuf提倾向于更希望是供给应用程序,从预先分配好的内存里面分配,Unpooled非池化每次进行内存分配的时候直接调用系统API向操作系统申请一块内存,直接分配
 * (2)Unsafe和非Unsafe:Unsafe直接获取ByteBuf在JVM内存地址基于内存地址调用JDK的Unsafe进行读写操作,使用UnsafeByteBufUtil.getByte(memory, idx(index))通过ByteBuf底层分配内存首地址和当前指针基于内存偏移地址获取对应的值,非Unsafe不依赖JDK底层的Unsafe对象,使用HeapByteBufUtil.getByte(array, index)通过内存数组和索引获取对应的值
 * (3)Heap和Direct:Heap在堆上进行内存分配,分配内存需要被GC管理无需手动释放内存,依赖底层byte数组,Direct调用JDK的API进行内存分配,分配内存不受JVM控制最终不会参与GC过程,需要手动释放内存避免造成内存无法释放,依赖DirectByteBuffer对象内存,分配工具:Unpooled#directBuffer()方法
 * <p>
 * ByteBufAllocator分析:ByteBuf通过ByteBufAllocator内存分配管理器分配内存,内存分配管理器最顶层抽象负责分配所有类型的内存
 * (1)ByteBufAllocator功能
 * ByteBufAllocator重载buffer()方法分配一块内存,是否为Direct/Heap内存依赖于具体实现,ioBuffer()方法分配一块内存,更希望是适合IO的Direct Buffer,directBuffer()/headBuffer()方法堆内/堆外进行内存分配,compositeBuffer()方法分配ByteBuf的时候,ByteBuf底层不基于Direct/Heap堆内/堆外内存进行实现,将两个ByteBuf合并在一起变成CompositeByteBuf
 * (2)AbstractByteBufAllocator
 * AbstractByteBufAllocator抽象实现ByteBufAllocator,buffer()方法分配Buffer依赖具体实现分配内存,调用directBuffer()/heapBuffer()方法分配DEFAULT_INITIAL_CAPACITY[256]默认Buffer容量和 Integer.MAX_VALUE最大扩充容量的ByteBuf,按照initialCapacity和maxCapacity参数使用newDirectBuffer()/newHeapBuffer()抽象方法分配堆内/堆外内存,是否为Pooled/Unpooled依赖底层实现即底层获取的分配器决定
 * (3)ByteBufAllocator两个子类
 * PooledByteBufAllocator内存分配器从预先分配好的内存取一段连续内存,UnpooledByteBufAllocator内存分配器调用系统API分配内存,底层调用hasUnsafe()方法判断是否能够获取Unsafe决定分配Unsafe/非Unsafe ByteBuf
 * <p>
 * UnpooledByteBufAllocator分析
 * (1)heap内存的分配
 *  newHeapBuffer()方法通过hasUnsafe()方法判断是否有Unsafe传递initialCapacity容量Byte数组参数setArray()方法设置array数组成员变量以及setIndex()方法设置读/写指针创建UnpooledUnsafeHeapByteBuf/UnpooledHeapByteBuf,UnpooledUnsafeHeapByteBuf/UnpooledHeapByteBuf的_get***()方法通过Unsafe方式返回数组对象偏移量[BYTE_ARRAY_BASE_OFFSET+index]对应的byte/数组索引方式返回array数组index位置byte
 * (2)direct内存的分配
 * newDirectBuffer()方法通过hasUnsafe()方法判断是否有Unsafe调用allocateDirect(initialCapacity)创建Direct ByteBuffer使用setByteBuffer()方法设置buffer成员变量[UnpooledUnsafeDirectByteBuf使用directBufferAddress()方法获取buffer内存地址设置memoryAddress成员变量]创建UnpooledUnsafeDirectByteBuf/UnpooledDirectByteBuf,UnpooledUnsafeDirectByteBuf/UnpooledDirectByteBuf的_get***()方法通过addr()方法基地址memoryAdress+偏移地址index计算内存地址Unsafe获取对应这块内存的byte/JDK底层ByteBuffer获取buffer index位置对应的byte
 * Unsafe通过内存地址+偏移量方式获取ByteBuffer的byte>非Unsafe通过数组+下标或者JDK底层ByteBuffer的API获取ByteBuffer的byte
 * <p>
 * PooledByteBufAllocator概述
 * (1)拿到线程局部缓存PoolThreadCache->threadCache.get()
 * 通过PoolThreadLocalCache类型成员变量threadCache的get()方法获取当前线程的PoolThreadCache局部缓存cache,不同线程在对象内存堆上进行分配,PoolThreadLocalCache[继承FastThreadLocal]的initialValue()方法通过heapArenas/directArenas调用leastUsedArena()方法获取heapArena/directArena数组构造PoolThreadLocalCache
 * (2)在线程局部缓存的Arena上进行内存分配
 * 线程局部缓存PoolThreadCache维护两块内存:heapArena/directArena堆内/堆外内存,初始化PoolThreadLocalCache通过heapArenas/directArenas调用leastUsedArena()方法获取用到最少的heapArena/directArena竞技场,heapArenas/directArenas通过构造PooledByteBufAllocator调用newArenaArray()方法根据DEFAULT_NUM_HEAP/DIRECT_ARENA[max(io.netty.allocator.numHeap/DirectArenas,min(runtime.availableProcessors()*2[默认使用2倍CPU核数减少同步不用加锁分配],runtime.maxMemory()/io.netty.allocator.pageSize << io.netty.allocator.maxOrder/2/3))]容量创建PoolArena数组遍历设置PoolArena的HeapArena/DirectArena
 * PooledByteBufAllocator结构
 * -----------      -----------     -----------     -----------
 * |Thread|      |Thread|     |Thread|     |Thread|
 * -----------      -----------     -----------     -----------
 * -----|---------------|---------------|--------------|-----
 * | ---------       ---------        ---------       --------- |
 * | |Arena|       |Arena|       |Arena|       |Arena| |
 * | ---------       ---------        ---------       --------- |
 * | -------------------------    tinyCacheSize           |
 * | |PoolThreadCache|    smallCacheSize        |
 * | -------------------------    normalCacheSize      |
 * ---------------------------------------------------------
 * 创建ByteBuffer通过PoolThreadCache获取Arena对象,PoolThreadCache通过ThreadLocal方式把内存分配器Arena塞到成员变量,每个线程调用get()方法的获取到对应的Arena即线程与Arena绑定,或者通过底层维护的ByteBuffer缓存列表譬如创建1024字节的ByteBuffer用完释放其他地方继续分配1024字节内存通过ByteBuffer缓存列表返回,PooledByteBufAllocator维护tinyCacheSize、smallCacheSize以及normalCacheSize缓存ByteBuffer的大小用来构造PooledByteBufAllocator使用createSubPageCaches()方法创建MemoryRegionCache数组缓存列表
 * <p>
 * directArena分配direct内存的流程->allocate()
 * (1)从对象池里面拿到PooledByteBuf进行复用->newByteBuf()
 * 调用newByteBuf()方法使用PooledUnsafeDirectByteBuf/PooledDirectByteBuf的newInstance()方法通过对象池RECYCLER的get()方法获取PooledDirectByteBuf对象调用reuse()方法复用按照指定maxCapacity扩容设置引用数量为1以及设置readerIndex/writerIndex读/写指针为0重置markedReaderIndex/markedWriterIndex标记读/写指针为0
 * (2)从缓存上进行内存分配->cache.allocateTiny()/allocateSmall()/allocateNormal()
 * ByteBuf之前使用过并且被release分配差不多规格大小ByteBuf当capacity<pageSize或者capacity<=chunkSize调用cache的allocateTiny()/allocateSmall()/allocateNormal()方法在缓存上进行内存分配
 * (3)从内存堆里面进行内存分配->allocateNormal()/allocateHuge()
 * 未命中缓存当capacity<pageSize或者capacity<=chunkSize调用allocateNormal()方法/当capacity>chunkSize调用allocateHuge()方法在内存堆里面进行内存分配
 * <p>
 * 2.不同规格大小和不同类别的内存的分配策略
 * 内存规格介绍
 * 0 <-tiny->512B<-small->8K<-normal->16M<-huge->
 *  |____________________|                        |
 *               SubPage          Page                Chunk
 * 16M作为分界点对应的Chunk,所有的内存申请以Chunk为单位向操作系统申请,内存分配在Chunk里面执行相应操作,16M Chunk按照Page进行切分为2048个Page,8K Page按照SubPage切分
 * 命中缓存的分配逻辑
 * (1)缓存数据结构
 * --------------------------------MemoryRegionCache-----------------------------------
 * | queue:      Chunk&Handler   Chunk&Handler    .....   Chunk&Handler  |
 * | sizeClass:  tiny(0~512B)        small(512B~8K)            normal(8K~16M) |
 * | size:              N*16B          512B、1K、2K、4K         8K、16K、32K  |
 * -----------------------------------------------------------------------------------------------
 * queue由Entry[Chunk&Handler,Handler指向唯一一段连续内存,Chunk+指向Chunk的一段连续内存确定Entry的内存大小和内存位置]组成Cache链,在缓存里查找有无对应的链定位到queue里面的Entry,sizeClass即内存规格包括tiny(0~512B)、small(512B~8K)以及normal(8K~16M),size即MemoryRegionCache缓存ByteBuf的大小,同一个MemoryRegionCache的queue里面所有元素都是固定的大小,包括tiny(N*16B)、small(512B、1K、2K、4K)以及normal(8K、16K、32K)
 * ---------------MemoryRegionCache--------------
 * | tiny[32]   0   16B 32B 48B ... 480B 496B |
 * | small[4]       512B      1K      2K      4K      |
 * | normal[3]          8K      16K     32K           |
 * ---------------------------------------------------------
 * queue存储每种大小的ByteBuf,sizeClass包括Tiny、Small以及Normal,同一个size的ByteBuf有哪些可以直接利用,每个线程维护PoolThreadCache涵盖tinySubPageHeap/DirectCaches、smallSubPageHeap/DirectCaches、normalHeap/DirectCaches三种内存规格大小缓存Cache,调用createSubPageCaches()/createNormalCaches()方法创建MemoryRegionCache数组
 * (2)命中缓存的分配流程
 * 申请内存调用normalizeCapacity()方法reqCapacity大于Tiny找2的幂次方数值确保数值大于等于reqCapacity,Tiny内存规格以16的倍数自增分段规格化,目的是为了缓存分配后续release放到缓存里面而不需要释放,调用cache的allocateTiny()/allocateSmall()/allocateNormal()方法分配缓存
 * (1)找到对应size的MemoryRegionCache->cacheForTiny()/cacheForSmall()/cacheForNormal()
 * 调用cacheForTiny()/cacheForSmall()/cacheForNormal()方法使用PoolArena的tinyIdx()/smallIdx()/log2(normCapacity>>numShiftsNormalDirect/numShiftsNormalHeap)方法计算索引通过数组下标方式获取缓存节点MemoryRegionCache
 * (2)从queue中弹出一个entry[chunk连续内存]给ByteBuf初始化
 * 调用queue的poll()方法弹出个entry使用initBuf()方法根据entry的chunk和handle通过initBuf()/initBufWithSubpage()方法调用PooledByteBuf的init()方法设置ByteBuf的chunk和handle给ByteBuf初始化
 * (3)将弹出的entry扔到对象池进行复用->entry.recycle()
 * 调用entry的recycle()方法设置chunk为null&handle为-1使用recyclerHandle的recycle()方法压到栈里扔到对象池后续ByteBuf回收从对象池获取entry将entry的chunk和handle指向被回收的ByteBuf进行复用减少GC以及减少对象重复创建和销毁
 * <p>
 * arena、chunk、page、subpage概念
 * PoolThreadCache
 * ---------------
 * | ----------- |
 * | | Cache | |
 * | ----------- |
 * | ----------- |
 * | | Arena | |
 * | ----------- |
 * --------------
 * Arena划分开辟连续内存进行分配,Cache直接缓存连续内存进行分配
 *                                     Arena
 * --------------------------------------------------------------------
 * | ---------------            ---------------            --------------- |
 * | | -----------  |            | -----------  |            | -----------  | |
 * | | | Chunk | |            | | Chunk | |            | | Chunk | | |
 * | | -----------  |            | -----------  |            | -----------  | |
 * | |       | |      |-------->|       | |      |-------->|       | |      | |
 * | | -----------  |            | -----------  |            | -----------  | |
 * | | | Chunk | |            | | Chunk | |            | | Chunk | | |
 * | | -----------  |            | -----------  |            | -----------  | |
 * | |       | |      |<--------|       | |      |<--------|       | |      | |
 * | | -----------  |            | -----------  |            | -----------  | |
 * | | | Chunk | |            | | Chunk | |            | | Chunk | | |
 * | | -----------  |            | -----------  |            | -----------  | |
 * | ---------------            ---------------            ---------------  |
 * |  ChunkList               ChunkList              ChunkList  |
 * --------------------------------------------------------------------
 * Arena的ChunkList[每个节点都是Chunk]通过链表方式连接并且每个ChunkList里面有对应的Chunk进行双向链表连接是因为实时计算每个Chunk的分配情况按照内存使用率分别归为ChunkList
 * PoolArena维护不同使用率的PoolChunkList即Chunk集合q100/q075/q050/q025/q000/qInit调用prevList()方法双向链表连接
 * ----------------------     ----------------------
 * | -------     ------- |      | -------     ------- |
 * | | 8K | ... | 8K | |      | | 2K | ... | 2K | |
 * | -------     ------- |      | -------     ------- |
 * | -------     ------- |      | -------     ------- |
 * | | 8K | ... | 8K | |      | | 2K | ... | 2K | |
 * | -------     ------- |      | -------     ------- |
 * ----------------------     ----------------------
 *        Chunk                    SubPage[]
 * Chunk以8K大小划分为Page,Page以2K大小划分为SubPage
 * PoolArena维护PoolSubpage tinySubpagePools/smallSubpagePools,PoolSubpage的chunk表示子页SubPage从属Chunk,elemSize表示子页SubPage划分数值,bitmap记录子页SubPage内存分配情况[0:未分配/1:已分配],prev/next表示子页SubPage以双向链表方式连接
 * 内存分配从线程的PoolThreadCache里面获取对应的Arena,Arena通过ChunkList获取Chunk进行内存分配,Chunk内存分配判断分配内存大小超过1个Page以Page为单位分配,远远小于1个Page获取Page切分成若干SubPage进行内存划分
 * <p>
 * page级别的内存分配:allocateNormal()
 * (1)尝试在现有的chunk上分配->allocate()
 * 调用PoolChunkList q050/q025/q000/qInit/q075的allocate()方法尝试在现有的chunk上分配,首次PoolChunkList为空无法在现有的chunk上分配,从head节点开始往下遍历每个chunk尝试分配获取handle,handle小于0表示未分配指向next节点继续分配,handle大于0调用chunk的initBuf()方法初始化PooledByteBuf并且判断chunk的使用率是否超过最大使用率从当前PoolChunk移除添加到nextList下一个链表
 * (2)创建一个chunk进行内存分配->newChunk()/chunk.allocate()
 * 调用newChunk()方法创建PoolChunk对象,通过PoolChunk的allocate()方法分配normCapacity内存获取handle指向chunk里面一块连续内存,通过allocateDirect()方法获取直接内存使用PoolChunk构造方法创建1<<maxOrder[11]容量memoryMap和depthMap一次一级地向下移动在每个级别遍历左到右并将值设置为子深度创建PoolChunk对象,调用PoolChunk对象的allocate()方法使用allocateRun()方法计算分配深度通过allocateNode()方法分配节点[从0层开始往下查询空闲节点即未使用且大小满足normCapacity的节点,调用setValue()方法标记节点为unusable[12]即已被使用,使用updateParentsAlloc()方法逐层往上查询父节点标记已被使用在内存中分配索引]
 * 0<-----------------------------0~16M
 * 1<------------------------0~8M    8~16M
 * 2<-------------------0~4M    4~8M    8~12M    12~16M
 *                                                    ...
 * 10<-----------0~16K    16K~32K    32K~48K ...
 * 11<------0~8K    8K~16K    16K~24K    24K~32K  ...
 * (3)初始化PooledByteBuf->initBuf()
 * 调用PoolChunk的initBuf()方法初始化PooledByteBuf即获取chunk的一块连续内存过后把对应的标记打到PooledByteBuf,调用memoryMapIdx()方法计算chunk连续内存在memoryMap的索引,使用bitmapIdx()方法计算chunk连续内存在bitMap的索引,通过runOffset(memoryMapIdx)计算偏移量以及runLength()方法计算最大长度调用PooledByteBuf的init()方法设置初始化PooledByteBuf
 * <p>
 * subpage级别的内存分配:allocateTiny()
 * 调用tinyIdx()方法计算normCapacity>>4获取tinySubpagePools的索引tableIdx,根据tableIdx获取tinySubpagePools下标位置的PoolSubpage头节点head,默认情况头节点head无任何内存信息指向它自己表示当前头节点head为空,头节点head的后置节点next非头节点head本身调用initBufWithSubpage()方法初始化PooledByteBuf,反之调用allocateNormal()方法进行subpage级别的内存分配
 * ------------------tinySubpagePools---------------
 * | tiny[32]   0   16B 32B 48B ... 480B 496B |
 * --------------------------------------------------------
 * (1)定位一个Subpage对象
 * 调用allocateNormal()方法内存分配通过PoolChunk对象的allocate()方法使用allocateSubpage()方法创建/初始化normCapacity容量新PoolSubpage添加到拥有此PoolChunk的PoolArena的子页面池里,调用arena的findSubpagePoolHead()方法获取PoolArena拥有的PoolSubPage池的头部并对头节点进行同步,子页面只能从页面分配根据maxOrder分配深度调用allocateNode()方法分配节点获取节点index,使用subpageIdx()方法获取SubPage的索引subpageIdx
 * ----------------------
 * | -------     ------- |
 * | | 8K | ... | 8K | |
 * | -------     ------- |
 * | -------     ------- |
 * | | 8K | ... | 8K | |
 * | -------     ------- |
 * ----------------------
 * Chunk中的SubPages
 * (2)初始化Subpage
 * 以SubPage的索引获取subpages数组subpageIdx位置的subpage,subpage为空调用PoolSubpage的构造方法创建PoolSubpage,使用init()方法pageSize/normCapacity计算最大SubPage划分数量初始化位图bitmap标识Subpage是否被分配初始化PoolSubpage,调用addToPool()方法把PoolSubpage添加到头节点head所在的链表子页面池,使用allocate()方法获取位图bitmap未被使用的Subpage,可用Subpage为0从子页面池移除Subpage,调用toHandle()方法将bitmapIdx转成为handle[对应Chunk里面第几个节点第几个Subpage即一块内存里面的哪一块连续内存]把memoryMapIdx作为低32位和bitmapIdx作为高32位
 * ------------------tinySubpagePools---------------
 * | tiny[32]   0   16B 32B 48B ... 480B 496B |
 * |                       | |                                         |
 * |                     16B                                       |
 * ---------------------------------------------------------
 *                  handle的构成
 *              0x40000000     00000000
 * 或           bitmapIdx
 * 或                                memoryMapIdx
 * 等价于   bitmapIdx      memoryMapIdx 拼接
 * (3)初始化PooledByteBuf
 * 调用PoolChunk的initBuf()方法初始化PooledByteBuf即获取chunk的一块连续内存过后把对应的标记打到PooledByteBuf,调用memoryMapIdx()方法计算chunk连续内存在memoryMap的索引,使用bitmapIdx()方法计算chunk连续内存在bitMap的索引,调用initBufWithSubpage()方法通过runOffset(memoryMapIdx)+(bitmapIdx & 0x3FFFFFFF)* subpage.elemSize计算偏移量以及Subpage划分数量调用PooledByteBuf的init()方法设置初始化PooledByteBuf
 * 3.内存的回收过程
 * 调用ByteBuf的release()方法使用AbstractReferenceCountedByteBuf的release0()方法判断引用数量是否等于decrement相等调用deallocate()方法设置handle为-1表示不指向任何一块内存以及memory设置为空
 * ByteBuf的释放
 * (1)连续的内存区段加到缓存->chunk.arena.free()
 * 调用chunk.arena.free()方法通过PoolThreadCache的add()方法把连续的内存区段[chunk&handle唯一标识]添加到缓存,使用PoolThreadCache的cache()方法获取MemoryRegionCache节点,调用MemoryRegionCache的add()方法把chunk和handle封装成Entry加到queue,通过newEntry()方法获取对象池RECYCLER的Entry调用queue的offer()方法添加到queue
 * (2)标记连续的内存区段为未使用->freeChunk()
 * 调用freeChunk()方法使用chunk.parent.free()方法通过Chunk释放连续内存,memoryMapIdx()/bitmapIdx()方法获取连续内存的memoryMapIdx/bitmapIdx,bitmapIdx不等于0表示释放SubPage子页面内存通过arena的findSubpagePoolHead()方法获取PoolSubPage头节点head调用subpage的free()方法释放把连续内存对应的位图标识为0,非SubPage通过分配内存反向标记将连续内存标记为未使用,Page级别完全二叉树,SubPage级别位图
 * (3)ByteBuf加到对象池->recycle()
 * 调用recycle()方法通过recyclerHandle的recycle()方法将ByteBuf加到对象池即PooledByteBuf被销毁之后在对象池里面
 * <p>
 * ByteBuf的api和分类:read**()/write**()方法,AbstractByteBuf实现ByteBuf的数据结构抽象出一系列和数据读写相关的api给子类实现,ByteBuf分类按照三个维度区分:1.堆内[基于2048byte字节内存数组分配]/堆外[基于JDK的DirectByteBuffer内存分配],2.Unsafe[通过JDK的Unsafe对象基于物理内存地址进行数据读写]/非Unsafe[调用JDK的API进行读写],3.UnPooled[每次分配内存申请内存]/Pooled[预先分配好一整块内存,分配的时候用一定算法从一整块内存取出一块连续内存]
 * 分配Pooled内存的总步骤:首先现成私有变量PoolThreadCache维护的缓存空间查找之前使用过被释放的内存,有的话基于连续内存进行分配,没有的话用一定算法在预先分配好的Chunk进行内存分配
 * 不同规格的pooled内存分配与释放:Page级别的内存分配和释放通过完全二叉树的标记查找某一段连续内存,Page级别以下的内存分配首先查找到Page然后把此Page按照SubPage大小进行划分最后通过位图的方式进行内存分配和释放,内存被释放的时候可能被加入到不同级别的缓存队列供下次分配使用
 * <p>
 * 内存的类别有哪些?1.堆内[基于byte字节内存数组分配]/堆外[基于JDK的DirectByteBuffer内存分配],2.Unsafe[通过JDK的Unsafe对象基于物理内存地址进行数据读写]/非Unsafe[调用JDK的API进行读写],3.UnPooled[每次分配内存申请内存]/Pooled[预先分配好一整块内存,分配的时候用一定算法从一整块内存取出一块连续内存]
 * 如何减少多线程内存分配之间的竞争?PooledByteBufAllocator内存分配器结构维护Arena数组,所有的内存分配都在Arena上进行,通过PoolThreadCache对象将线程和Arena进行一一绑定,默认情况一个Nio线程管理一个Arena实现多线程内存分配相互不受影响减少多线程内存分配之间的竞争
 * 不同大小的内存是如何进行分配的?Page级别的内存分配和释放通过完全二叉树的标记查找某一段连续内存,Page级别以下的内存分配首先查找到Page然后把此Page按照SubPage大小进行划分最后通过位图的方式进行内存分配和释放,内存被释放的时候可能被加入到不同级别的缓存队列供下次分配使用
 */

/**
 * Netty解码:将一串二进制数据流解析成有各自定义的数据包ByteBuf,解码器把二进制数据流解析成ByteBuf即自定义的协议数据包扔给业务逻辑处理
 * 1.解码器基类-实现抽象的解码过程
 * ByteToMessageDecoder[基于ByteBuf解码]解码步骤
 * (1)累加字节流->cumulate()
 * 设置first为累加器cumulation是否为空,累加器cumulation为空表示第一次从io流读取数据赋值为读进来的ByteBuf对象,累加器cumulation非空表示非第一次从io流读取数据调用cumulator[即MERGE_CUMULATOR Cumulator对象]的cumulate()方法判断当前写指针往后移in.readableBytes()字节超过累加器cumulation的maxCapacity表示当前cumulation无法写字节进行扩容,未超过maxCapacity则buffer赋值为当前cumulation,通过ByteBuffer的writeBytes()方法把当前数据写到累加器里面,使用release()方法把读进来的数据释放,把当前累加器里面的数据和读进来的数据进行累加
 * (2)调用子类的decode方法进行解析->callDecode()
 * 调用callDecode()方法将累加器cumulation数据解析到的对象放到CodecOutputList向下进行传播,循环判断累加器里面是否有数据,CodecOutputList有对象通过调用fireChannelRead()方法向下进行事件传播并且将CodecOutputList清空,获取当前可读字节的长度oldInputLength调用decode()方法ByteBuf解码根据不同协议子类的解码器把当前读到的所有数据即累加器里面的数据取出二进制数据流解析放进CodecOutputList,判断CodecOutputList的大小是否等于decode的CodecOutputList大小,相等表示通过decode()方法未解析出对象判断oldInputLength是否等于in.readableBytes(),相等表示通过decode()方法未没有从in里面读取数据即当前累加器里面数据不足以拼装一个完整的数据包,本次没有足够的数据包只有之后再读取数据解析break跳出循环,不等表示从当前in读取数据未解析到对象继续循环,通过decode()方法解析出数据oldInputLength等于in.readableBytes()表示未从累加器里面读取数据抛DecoderException异常
 * (3)将解析到的ByteBuf向下传播
 * 获取CodecOutputList长度调用fireChannelRead()方法循环遍历CodecOutputList通过事件传播机制ctx.fireChannelRead()调用CodecOutputList的getUnsafe()方法获取ByteBuf把解析到的ByteBuf对象向下进行传播给ChannelHandler进行业务逻辑处理并且回收CodecOutputList
 * 2.Netty中常见的解码器分析
 * (1)基于固定长度解码器分析
 * 基于固定长度解码器FixedLengthFrameDecoder成员变量frameLength表示固定长度解码器以多少长度分割进行解析,构造FixedLengthFrameDecoder传参frameLength赋值给成员变量frameLength保存,调用decode()方法解析累加器cumulation数据把数据包添加到CodecOutputList,判断当前累加器里面可读字节是否小于frameLength,小于返回空表示没有解析出对象往CodecOutputList存放数据并且没有从累加器里面读取数据,反之调用readRetainedSlice()方法从当前累加器里面截取frameLength长度ByteBuf返回从当前readerIndex开始增加frameLength长度的Buffer子区域新保留切片
 * +---+----+------+----+
 * | A | BC | DEFG | HI |
 * +---+----+------+----+
 *               | |
 * +-----+-----+-----+
 * | ABC | DEF | GHI |
 * +-----+-----+-----+
 * <p>
 * (2)基于行解码器分析
 * 基于行解码器LineBasedFrameDecoder成员变量maxLength[行解码器解析数据包最大长度],failFast[超越最大长度是否立即抛异常],stripDelimiter[解析数据包是否带换行符,换行符支持\n和\r],discarding[解码过程是否处于丢弃模式],discardedBytes[解码过程丢弃字节],调用decode()方法解析累加器cumulation数据把数据包添加到CodecOutputList,通过findEndOfLine()方法从累加器ByteBuf里面查找获取行的结尾eol即\n或者\r\n,非丢弃模式查找到行的结尾eol计算从换行符到可读字节的长度length和分隔符的长度判断length是否大于解析数据包最大长度maxLength,大于maxLength则readerIndex指向行的结尾eol后面的可读字节丢弃数据通过fail()方法传播异常,判断分隔符是否算在完整数据包范畴从buffer分割length长度获取新保留切片并且buffer跳过分隔符readerIndex指向分隔符后面的可读字节,未查找到行的结尾eol获取可读字节长度length超过解析数据包最大长度maxLength赋值discardedBytes为length读指针readerIndex指向写指针writerIndex赋值discarding为true进入丢弃模式并且根据failFast调用fail()方法立即传播fail异常,丢弃模式查找到行的结尾eol按照丢弃字节+从换行符到可读字节的长度计算length和换行符长度将readerIndex指向丢弃字节换行符后面的可读字节赋值discardedBytes为0并且discarding为false表示当前属于非丢弃模式,根据failFast调用fail()方法立即传播fail异常,未查找到行的结尾eol则discardedBytes增加buffer的可读字节并且读指针readerIndex指向写指针writerIndex
 *                                  非丢弃模式处理
 * .   ------   ------   ------   ------   ------   ------   ------   ------   ------
 * ... |     |   |     |   |     |   | \n  |   |     |   |     |   |     |   | \r  |   | \n  | ...
 *     ------   ------   ------   ------   ------   ------   ------   ------   ------
 *        |                              |                                       |
 *  readerIndex                 eol                                    eol
 * .   ------   ------   ------   ------   ------   ------   ------   ------   ------
 * ... |     |   |     |   |     |    |     |   |     |   |     |   |     |   |     |   |     | ...
 *     ------   ------   ------   ------   ------   ------   ------   ------   ------
 *        |                                                                                |
 *  readerIndex                                                            writerIndex
 *                                   丢弃模式处理
 * .   ------   ------   ------   ------   ------   ------   ------   ------   ------
 * ... |     |   |     |   |     |   | \n  |   |     |   |     |   |     |   | \r  |   | \n  | ...
 *     ------   ------   ------   ------   ------   ------   ------   ------   ------
 *        |                              |                                       |
 *  readerIndex                 eol                                    eol
 * .   ------   ------   ------   ------   ------   ------   ------   ------   ------
 * ... |     |   |     |   |     |    |     |   |     |   |     |   |     |   |     |   |     | ...
 *     ------   ------   ------   ------   ------   ------   ------   ------   ------
 *        |                                                                                |
 *  readerIndex                                                            writerIndex
 *  <p>
 * (3)基于分隔符解码器分析
 * 基于分隔符解码器DelimiterBasedFrameDecoder成员变量maxFrameLength表示解析数据包最大长度,delimiters表示分隔符数组,调用decode()方法解析累加器cumulation数据把数据包添加到CodecOutputList
 * <1>行处理器
 * 分隔符解码器DelimiterBasedFrameDecoder构造方法调用isLineBased()方法判断delimiters为基于行的分隔符创建行解码器LineBasedFrameDecoder赋值成员变量lineBasedDecoder行处理器,行处理器lineBasedDecoder非空调用行解码器LineBasedFrameDecoder的decode()方法解析ByteBuf
 * <2>找到最小分隔符
 * 遍历分隔符数组delimiters通过indexOf()方法获取每个分隔符分割的数据包长度计算分隔符分割的最小数据包长度minFrameLength找到最小分隔符minDelim
 * .   ------   ------   ------   ------   ------   ------   ------   ------   ------
 * ... |     |   |     |   |     |    |     |   |     |   |  A  |  |     |   |  B  |  |     | ...
 *     ------   ------   ------   ------   ------   ------   ------   ------   ------
 *        |
 *  readerIndex
 * <3>解码
 * 找到最小分隔符minDelim获取最小分隔符长度minDelimLength判断discardingTooLongFrame是否为true表示当前是否处于丢弃模式,丢弃模式标记discardingTooLongFrame为false表示当前已为非丢弃模式跳过minFrameLength+minDelimLength长度字节并且failFast为false即前面未抛异常调用fail()方法立即抛TooLongFrameException异常,非丢弃模式判断最小数据包长度minFrameLength是否大于maxFrameLength,大于调用skipBytes()方法丢弃minFrameLength+minDelimLength长度字节数据包readerIndex指向最小分隔符后面字节并且调用fail()方法抛异常,判断解析出的数据包是否包含分隔符从buffer分割minFrameLength长度获取新保留切片并且buffer跳过分隔符readerIndex指向分隔符后面字节,未找到最小分隔符minDelim判断discardingTooLongFrame是否为true表示当前是否处于丢弃模式,非丢弃模式判断buffer可读字节是否大于maxFrameLength,大于maxFrameLength获取buffer可读字节长度tooLongFrameLength调用skipBytes()方法跳过buffer可读字节赋值discardingTooLongFrame为true标记当前处于丢弃模式并且调用fail()方法立即抛TooLongFrameException异常,丢弃模式tooLongFrameLength累加buffer可读字节调用skipBytes()方法跳过buffer可读字节
 *                                   找到分隔符
 * .   ------   ------   ------   ------   ------   ------   ------   ------   ------
 * ... |     |   |     |   |     |    |     |   |     |   |  0  |  |     |   |     |  |     | ...
 *     ------   ------   ------   ------   ------   ------   ------   ------   ------
 *        |                                                  |
 *  readerIndex
 *                                 未找到分隔符
 * .   ------   ------   ------   ------   ------   ------   ------   ------   ------
 * ... |     |   |     |   |     |    |     |   |     |   |     |   |     |   |     |   |     | ...
 *     ------   ------   ------   ------   ------   ------   ------   ------   ------
 *        |                                                                                 |
 *  readerIndex                                                              writerIndex
 * +--------------+
 * | ABC\nDEF\r\n |
 * +--------------+
 *          | |
 * +-----+-----+
 * | ABC | DEF |
 * +-----+-----+
 * <p>
 * (4)基于长度域解码器分析
 * 基于长度域解码器LengthFieldBasedFrameDecoder成员变量lengthFieldOffset[长度域偏移量即长度域在二进制数据流里面偏移量],lengthFieldLength[长度域长度即从长度域开始往后几个字节组合起来表示长度],lengthAdjustment[长度域表示长度+额外调整长度=数据包长度即长度域计算完整数据包长度,长度额外调整],initialBytesToStrip[decode出完整数据包之后向下传播的时候是否需要砍掉几个字节即解析数据包前面跳过字节]
 *                                 重要参数
 * ------   ------   ------   ------   ------   ------   ------   ------   ------
 * |     |   |     |   | 00 |  | 04 |   |     |   |     |   |     |   |     |   |     |
 * ------   ------   ------   ------   ------   ------   ------   ------   ------
 * |                                                                             |
 * -----------------------------------------------------------------
 * ----------------------------     -----------------------------
 * | lengthFieldOffset:2 |     | lengthFieldLength:2 |
 * ----------------------------     -----------------------------
 * lengthFieldOffset = 0
 * lengthFieldLength = 2
 * lengthAdjustment = 0
 * initialBytesToStrip = 0 (= do not strip header)
 * BEFORE DECODE (14 bytes)         AFTER DECODE (14 bytes)
 * +--------+----------------+      +--------+----------------+
 * | Length | Actual Content |----->| Length | Actual Content |
 * | 0x000C | "HELLO, WORLD" |      | 0x000C | "HELLO, WORLD" |
 * +--------+----------------+      +--------+----------------+
 * lengthFieldOffset = 0
 * lengthFieldLength = 2
 * lengthAdjustment = 0
 * initialBytesToStrip = 2 (= the length of the Length field)
 * BEFORE DECODE (14 bytes)         AFTER DECODE (12 bytes)
 * +--------+----------------+      +----------------+
 * | Length | Actual Content |----->| Actual Content |
 * | 0x000C | "HELLO, WORLD" |      | "HELLO, WORLD" |
 * +--------+----------------+      +----------------+
 * lengthFieldOffset   =  0
 * lengthFieldLength   =  2
 * lengthAdjustment = -2 (= the length of the Length field)
 * initialBytesToStrip =  0
 * BEFORE DECODE (14 bytes)         AFTER DECODE (14 bytes)
 * +--------+----------------+      +--------+----------------+
 * | Length | Actual Content |----->| Length | Actual Content |
 * | 0x000E | "HELLO, WORLD" |      | 0x000E | "HELLO, WORLD" |
 * +--------+----------------+      +--------+----------------+
 * lengthFieldOffset = 2 (= the length of Header 1)
 * lengthFieldLength = 3
 * lengthAdjustment    = 0
 * initialBytesToStrip = 0
 * BEFORE DECODE (17 bytes)                      AFTER DECODE (17 bytes)
 * +----------+----------+----------------+      +----------+----------+----------------+
 * | Header 1 |  Length  | Actual Content |----->| Header 1 |  Length  | Actual Content |
 * |  0xCAFE  | 0x00000C | "HELLO, WORLD" |      |  0xCAFE  | 0x00000C | "HELLO, WORLD" |
 * +----------+----------+----------------+      +----------+----------+----------------+
 * lengthFieldOffset = 0
 * lengthFieldLength = 3
 * lengthAdjustment = 2 (= the length of Header 1)
 * initialBytesToStrip = 0
 * BEFORE DECODE (17 bytes)                      AFTER DECODE (17 bytes)
 * +----------+----------+----------------+      +----------+----------+----------------+
 * |  Length  | Header 1 | Actual Content |----->|  Length  | Header 1 | Actual Content |
 * | 0x00000C |  0xCAFE  | "HELLO, WORLD" |      | 0x00000C |  0xCAFE  | "HELLO, WORLD" |
 * +----------+----------+----------------+      +----------+----------+----------------+
 * lengthFieldOffset = 1 (= the length of HDR1)
 * lengthFieldLength = 2
 * lengthAdjustment = 1 (= the length of HDR2)
 * initialBytesToStrip = 3 (= the length of HDR1 + LEN)
 * BEFORE DECODE (16 bytes)                       AFTER DECODE (13 bytes)
 * +------+--------+------+----------------+      +------+----------------+
 * | HDR1 | Length | HDR2 | Actual Content |----->| HDR2 | Actual Content |
 * | 0xCA | 0x000C | 0xFE | "HELLO, WORLD" |      | 0xFE | "HELLO, WORLD" |
 * +------+--------+------+----------------+      +------+----------------+
 * lengthFieldOffset = 1
 * lengthFieldLength = 2
 * lengthAdjustment = -3 (= the length of HDR1 + LEN, negative)
 * initialBytesToStrip = 3
 * BEFORE DECODE (16 bytes)                       AFTER DECODE (13 bytes)
 * +------+--------+------+----------------+      +------+----------------+
 * | HDR1 | Length | HDR2 | Actual Content |----->| HDR2 | Actual Content |
 * | 0xCA | 0x0010 | 0xFE | "HELLO, WORLD" |      | 0xFE | "HELLO, WORLD" |
 * +------+--------+------+----------------+      +------+----------------+
 * 基于长度域解码器步骤
 * <1>计算需要抽取的数据包长度
 * 判断数据包可读字节是否小于lengthFieldEndOffset,小于lengthFieldEndOffset表示当前可读字节流未到达长度域结尾,通过readerIndex+lengthFieldOffset计算实际长度域偏移量,调用getUnadjustedFrameLength()方法从字节流里面actualLengthFieldOffset偏移量开始往后读lengthFieldLength长度字节获取未调整的数据包长度frameLength,数据包长度frameLength累加lengthAdjustment+lengthFieldEndOffset获取当前解码需要从二进制流数据流里面抽取出来的二进制数据包长度,判断数据包可读字节是否小于从当前数据流截取frameLengthInt数据,小于表示当前二进制数据流是不完整数据包
 *                            长度域解码示例
 * ------   ------   ------   ------   ------   ------   ------   ------   ------
 * |     |   |     |   | 00 |  | 08 |   |     |   |     |   |     |   |     |   |     |
 * ------   ------   ------   ------   ------   ------   ------   ------   ------
 *         lengthFieldEndOffset|                                    |
 *                                          --------------------------------
 * ----------------------------     -----------------------------
 * | lengthFieldOffset:2 |     | lengthFieldLength:2 |
 * ----------------------------     -----------------------------
 * ----------------------------     -----------------------------
 * | lengthAdjustment:-4 |    | initialBytesToStrip:4 |
 * ----------------------------     -----------------------------
 * <2>跳过字节逻辑处理
 * 判断需要从当前数据包跳过字节数量initialBytesToStrip是否大于frameLengthInt,大于调用skipBytes()方法跳过frameLengthInt长度字节,调用skipBytes()方法从readerIndex跳过initialBytesToStrip字节获取当前readerIndex,通过frameLengthInt-initialBytesToStrip获取实际数据包长度actualFrameLength,使用extractFrame()方法从当前数据流里面readerIndex开始抽取actualFrameLength长度字节获取完整数据包,readerIndex向后移actualFrameLength长度字节
 * <3>丢弃模式下的处理
 * 计算抽取出的数据包长度大于指定maxFrameLength需要丢弃处理,判断当前需要抽取出来的数据包长度frameLength是否大于maxFrameLength进行丢弃模式处理通过数据包长度frameLength-当前可读字节获取还需要丢弃字节discard,赋值tooLongFrameLength为frameLength,discard小于0调用skipBytes()方法跳过frameLength长度字节,discard大于或者等于0赋值discardingTooLongFrame为true设置当前处于丢弃模式以及bytesToDiscard为discard表示经过本次处理之后后续还需要discard字节,调用skipBytes()方法跳过当前可读字节,调用failIfNecessary()方法判断bytesToDiscard是否等于0,bytesToDiscard等于0表示经过本次处理后续数据包有可能是正常的赋值tooLongFrameLength为0并且discardingTooLongFrame为false表示进入非丢弃模式,判断首次调用是否需要快速失败调用fail()方法立即抛异常,bytesToDiscard大于0判断failFast是否为true并且首次丢弃firstDetectionOfTooLongFrame调用fail()方法立即抛异常,丢弃模式获取还需要丢弃字节bytesToDiscard和当前可读字节的最小值localBytesToDiscard即真正需要丢弃字节,调用skipBytes()方法丢弃localBytesToDiscard长度字节标记下次还需要丢弃字节赋值给bytesToDiscard,调用failIfNecessary()方法决定是否需要抛异常本次非第一次监控到数据包长度需要丢弃
 * <p>
 * 解码器抽象的解码过程?通过解码器ByteToMessageDecoder解码实现,ByteToMessageDecoder解码步骤:1.累加字节流:把当前读到的所有字节流累加到累加器里面,2.调用子类的decode()方法进行解析,ByteToMessageDecoder调用子类的decode()方法传参当前累加字节流和CodecOutputList,子类解码从字节流里面读取一段数据,解析出的数据包加到CodecOutputList,3.将解析到ByteBuf向下传播:CodecOutputList有解析出的数据包通过Pipeline事件传播机制往下传播
 * Netty里面有哪些拆箱即用的解码器?基于固定长度解码器FixedLengthFrameDecoder[每次取固定长度的数据包,数据流长度足够截取一段数据包放到CodecOutputList],基于行解码器LineBasedFrameDecoder[\n或者|r\n为分隔符解码,丢弃/非丢弃模式处理和是/否找到分割符四个维度进行解码器的处理],基于分隔符解码器DelimiterBasedFrameDecoder[传参指定分隔符,找到分隔符使得本次解码出来的数据包长度最小,分隔符为\n或者\r\n委托给基于行解码器LineBasedFrameDecoder],基于长度域解码器LengthFieldBasedFrameDecoder[计算需要抽取的数据包长度即本次需要从当前数据流里面截取数据长度,跳过字节逻辑处理,丢弃模式下的处理]
 */
public class Scratch {
    public static void main(String[] args) {
        PooledByteBufAllocator allocator = PooledByteBufAllocator.DEFAULT;
        //int page = 1024 * 8;
        //allocator.directBuffer(2 * page);
        //allocator.directBuffer(16);
        ByteBuf byteBuf = allocator.directBuffer(16);
        byteBuf.release();
    }
}