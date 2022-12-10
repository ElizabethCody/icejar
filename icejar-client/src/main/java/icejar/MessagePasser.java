package icejar;

import icejar.MessagePassing;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.Map;
import java.util.HashMap;
import java.util.Optional;


final class MessagePasser {

    private static final Map<String, Map<String, Map<String, Receiver<?>>>> receivers = new HashMap<>();

    private MessagePasser() {}

    // Make sure that the nested mapping for the given server and module exists
    private static synchronized <T> void createMappingFor(
            Map<String, Map<String, Map<String, T>>> map,
            String serverName, String moduleName)
    {
        if (!map.containsKey(serverName)) {
            map.put(serverName, new HashMap<>());
        }

        if (!map.get(serverName).containsKey(moduleName)) {
            map.get(serverName).put(moduleName, new HashMap<>());
        }
    }

    static synchronized <T> Receiver<T> createReceiver(
            String serverName, String moduleName, String channel,
            Class<T> cls, Consumer<T> handler)
    {
        Receiver<T> receiver = new Receiver<>(cls, handler);

        createMappingFor(receivers, serverName, moduleName);
        receivers.get(serverName).get(moduleName).put(channel, receiver);

        return receiver;
    }

    static synchronized Receiver<?> getReceiver(
            String serverName, String moduleName, String channel)
    {
        return Optional.ofNullable(receivers.get(serverName))
            .map(m -> m.get(moduleName))
            .map(m -> m.get(channel))
            .orElse(null);
    }

    static synchronized void removeServer(String serverName) {
        receivers.remove(serverName);
    }

    static synchronized void removeModule(
            String serverName, String moduleName)
    {
        Optional.ofNullable(receivers.get(serverName))
            .ifPresent(m -> m.remove(moduleName));
    }

    static synchronized <T> Sender<T> createSender(
            String serverName, String moduleName, String channel)
    {
        return new Sender<>(serverName, moduleName, channel);
    }

    static Coordinator createCoordinator(String serverName, String moduleName) {
        return new Coordinator(serverName, moduleName);
    }

    static class Coordinator implements MessagePassing.Coordinator {

        private final String serverName;
        private final String moduleName;

        private Coordinator(String serverName, String moduleName) {
            this.serverName = serverName;
            this.moduleName = moduleName;
        }

        public <T> Sender<T> getSender(String moduleName, String channel) {
            return MessagePasser.createSender(serverName, moduleName, channel);
        }

        public <T> Sender<T> getSender(String moduleName) {
            return getSender(moduleName, "");
        }

        public <T> Receiver<T> getReceiver(
                String channel, Class<T> cls, Consumer<T> handler)
        {
            return MessagePasser.createReceiver(serverName, moduleName, channel, cls, handler);
        }

        public <T> Receiver<T> getReceiver(Class<T> cls, Consumer<T> handler) {
            return getReceiver("", cls, handler);
        }

        public <T> Receiver<T> getReceiver(String channel, Class<T> cls) {
            return MessagePasser.createReceiver(serverName, moduleName, channel, cls, null);
        }

        public <T> Receiver<T> getReceiver(Class<T> cls) {
            return getReceiver("", cls, null);
        }
    }

    static class Sender<T> implements MessagePassing.Sender<T> {

        private final String serverName;
        private final String moduleName;
        private final String channel;

        public Sender(String serverName, String moduleName, String channel) {
            this.serverName = serverName;
            this.moduleName = moduleName;
            this.channel = channel;
        }

        public boolean send(T message) {
            Receiver<?> receiver = MessagePasser.getReceiver(
                    serverName, moduleName, channel);

            if (receiver != null) {
                return receiver.handle(message);
            } else {
                return false;
            }
        }
    }

    static class Receiver<T> implements MessagePassing.Receiver<T> {

        private Consumer<T> handler;
        private final Class<T> cls;
        private final ReadWriteLock rwLock;

        public Receiver(Class<T> cls, Consumer<T> handler) {
            this.cls = cls;
            this.handler = handler;
            rwLock = new ReentrantReadWriteLock();
        }

        public void setHandler(Consumer<T> handler) {
            Lock lock = rwLock.writeLock();
            lock.lock();

            this.handler = handler;

            lock.unlock();
        }

        private static <M> M convertMessage(Object message, Class<M> cls) throws Exception {
            return ClassConverter.convert(message, cls);
        }

        public boolean handle(Object messageO) {
            T message;
            try {
                message = convertMessage(messageO, cls);
            } catch (Exception e) {
                return false;
            }

            Lock lock = rwLock.readLock();
            lock.lock();

            if (handler != null) {
                handler.accept(message);
                lock.unlock();
                return true;
            } else {
                lock.unlock();
                return false;
            }
        }
    }
}
