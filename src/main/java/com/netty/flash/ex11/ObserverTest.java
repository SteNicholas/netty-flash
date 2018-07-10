package com.netty.flash.ex11;

import io.netty.channel.ChannelFuture;
import io.netty.channel.socket.nio.NioSocketChannel;

import java.util.ArrayList;
import java.util.List;

/**
 * Netty设计模式的应用:
 * 观察者模式:
 * 1.观察者和被观察者
 * 2.观察者订阅消息,被观察者发布消息
 * 3.订阅则能收到被观察者发布的消息,取消订阅则收不到被观察者发布的消息
 * 应用范例:
 * @see io.netty.channel.socket.nio.NioSocketChannel#writeAndFlush(Object)
 * 观察者:GenericFutureListener,被观察者:ChannelFuture[ChannelPromise],注册观察者[向被观察者添加观察者]:addListener0(),通知观察者:[invokeWrite0()->safeSetFailure()->setFailure()/invokeFlush0()->safeSuccess()->trySuccess()]->notifyListener0()
 */
public class ObserverTest {
    /**
     * 被观察者
     */
    public interface Observerable {
        /**
         * 注册观察者
         *
         * @param observer
         */
        void registerObserver(Observer observer);

        /**
         * 移除观察者
         *
         * @param observer
         */
        void removeObserver(Observer observer);

        /**
         * 通知观察者有消息更新
         */
        void notifyObserver();
    }

    /**
     * 观察者
     */
    public interface Observer {
        void notify(String message);
    }

    public static class Girl implements Observerable {
        private String message;

        List<Observer> observerList;

        public Girl() {
            observerList = new ArrayList<>();
        }

        @Override
        public void registerObserver(Observer observer) {
            observerList.add(observer);
        }

        @Override
        public void removeObserver(Observer observer) {
            observerList.remove(observer);
        }

        @Override
        public void notifyObserver() {
            for (Observer observer : observerList) {
                observer.notify(message);
            }
        }

        public void hasBoyFriend() {
            message = "女神有男朋友了";
            notifyObserver();
        }

        public void getMarried() {
            message = "女神结婚了，你们都死心吧!";
            notifyObserver();
        }

        public void getSingled() {
            message = "女神单身了，你们有机会了!";
            notifyObserver();
        }
    }

    /**
     * 男孩
     */
    public static class Boy implements Observer {
        public void notify(String message) {
            System.out.println("男孩收到消息：" + message);
        }
    }

    /**
     * 男人
     */
    public static class Man implements Observer {
        public void notify(String message) {
            System.out.println("男人收到消息：" + message);
        }
    }

    /**
     * 老男人
     */
    public static class OldMan implements Observer {
        public void notify(String message) {
            System.out.println("老男人收到消息：" + message);
        }
    }

    public static void main(String[] args) {
        Girl girl = new Girl();
        Boy boy = new Boy();
        Man man = new Man();
        OldMan oldMan = new OldMan();

        // 通知男孩、男人、老男人，女神有男朋友了
        girl.registerObserver(boy);
        girl.registerObserver(man);
        girl.registerObserver(oldMan);
        girl.hasBoyFriend();
        System.out.println("====================");

        // 通知男孩，男人，女神结婚了
        girl.removeObserver(oldMan);
        girl.getMarried();
        System.out.println("====================");


        girl.registerObserver(oldMan);
        girl.getSingled();
    }


    public void write(NioSocketChannel channel, Object object) {
        ChannelFuture channelFuture = channel.writeAndFlush(object);
        channelFuture.addListener(future -> {
            if (future.isSuccess()) {

            } else {

            }
        });
        channelFuture.addListener(future -> {
            if (future.isSuccess()) {

            } else {

            }
        });
        channelFuture.addListener(future -> {
            if (future.isSuccess()) {

            } else {

            }
        });
    }
}