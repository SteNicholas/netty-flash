package com.netty.flash.ex10;

import io.netty.util.concurrent.FastThreadLocal;

/**
 * Netty两大性能优化工具类:
 * 1.FastThreadLocal:多线程访问同一变量的时候能够通过线程本地化方式避免多线程竞争,在保证状态一致性的同时优化程序性能,重新实现JDK ThreadLocal功能并且访问速度更快,解决线程变量隔离场景
 * FastThreadLocal的使用:
 * (1)每个线程获取FastThreadLocal的get()方法对象是线程独享的;
 * (2)一个线程对FastThreadLocal变量的set()方法修改不影响其他线程,FastThreadLocal对象在同一线程里实现状态一致,并且通过隔离线程之间变量方式优化程序性能
 * FastThreadLocal的实现机制:
 * (1)FastThreadLocal的创建
 * 通过InternalThreadLocalMap的nextVariableIndex()方法原子方式获取当前索引值设置FastThreadLocal的索引index作为唯一身份标识
 * (2)FastThreadLocal的get()方法
 * [1]获取ThreadLocalMap->InternalThreadLocalMap.get()
 * <1>获取当前线程判断线程是否为FastThreadLocalThread
 * <2>普通线程调用slowGet()方法slowThreadLocalMap通过JDK ThreadLocal方式获取InternalThreadLocalMap,首次调用get()方法创建InternalThreadLocalMap调用slowThreadLocalMap的set()方法设置
 * <3>FastThreadLocalThread线程调用fastGet()方法直接获取FastThreadLocalThread线程对象的threadLocalMap,首次调用get()方法创建InternalThreadLocalMap调用FastThreadLocalThread的setThreadLocalMap()方法设置
 * [2]直接通过索引取出对象
 * 调用当前线程threadLocalMap的indexedVariable()方法通过当前FastThreadLocal的索引index按照数组下标方式获取数组变量indexedVariables索引位置对象
 * [3]初始化:通常在线程首次调用FastThreadLocal的get()方法触发
 * <1>构造ThreadLocalMap调用newIndexedVariableTable()方法创建数组长度为32的UNSET对象数组设置初始化indexedVariables
 * <2>调用initialize()方法使用自定义initialValue()方法获取初始值,使用threadLocalMap的setIndexedVariable()设置indexedVariables数组索引index位置元素值
 * (3)FastThreadLocal的set()方法
 * [1]获取ThreadLocalMap->InternalThreadLocalMap.get()
 * [2]直接通过索引取出对象
 * 判断value是否为UNSET对象,非UNSET对象调用threadLocalMap的setIndexedVariable()设置indexedVariables数组当前索引index位置元素值
 * [3]remove对象
 * value为UNSET对象调用removeIndexedVariable()方法设置当前索引index位置indexedVariables元素为UNSET,如果threadLocalMap原来的对象为正常对象回调自定义onRemoval()方法
 */
public class FastThrealLocalTest {
    private static FastThreadLocal<Object> threadLocal0 = new FastThreadLocal<Object>() {
        @Override
        protected Object initialValue() throws Exception {
            return new Object();
        }

        @Override
        protected void onRemoval(Object value) throws Exception {
            System.out.println("onRemoval");
        }
    };

    private static FastThreadLocal<Object> threadLocal1 = new FastThreadLocal<Object>() {
        @Override
        protected Object initialValue() {
            return new Object();
        }
    };

    public static void main(String[] args) {
        new Thread(() -> {
            Object object = threadLocal0.get();
            // .... do with object
            System.out.println(object);

            while (true) {
                threadLocal0.set(new Object());
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }).start();

        new Thread(() -> {
            Object object = threadLocal0.get();
            // .... do with object
            System.out.println(object);
            while (true) {
                System.out.println(threadLocal0.get() == object);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }
}