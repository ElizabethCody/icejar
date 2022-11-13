package icejar;

import icejar.MessagePassing;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.Map;
import java.util.HashMap;
import java.util.Optional;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Constructor;


final class MessagePasser {

    private static Map<String, Map<String, Map<String, Receiver<?>>>> receivers = new HashMap<>();

    private MessagePasser() {}

    // Make sure that the nested mapping for the given server and module exists
    private static synchronized <T> void createMappingFor(
            Map<String, Map<String, Map<String, T>>> map,
            String serverName, String moduleName)
    {
        if (!map.containsKey(serverName)) {
            map.put(serverName, new HashMap<String, Map<String, T>>());
        }

        if (!map.get(serverName).containsKey(moduleName)) {
            map.get(serverName).put(moduleName, new HashMap<String, T>());
        }
    }

    protected static synchronized <T> Receiver<T> createReceiver(
            String serverName, String moduleName, String channel, Class<T> cls)
    {
        Receiver<T> receiver = new Receiver<>(cls);

        createMappingFor(receivers, serverName, moduleName);
        receivers.get(serverName).get(moduleName).put(channel, receiver);

        return receiver;
    }

    protected static synchronized Receiver<?> getReceiver(
            String serverName, String moduleName, String channel)
    {
        return Optional.ofNullable(receivers.get(serverName))
            .map(m -> m.get(moduleName))
            .map(m -> m.get(channel))
            .orElse(null);
    }

    protected static synchronized void removeServer(String serverName) {
        receivers.remove(serverName);
    }

    protected static synchronized void removeModule(
            String serverName, String moduleName)
    {
        Optional.ofNullable(receivers.get(serverName))
            .ifPresent(m -> m.remove(moduleName));
    }

    protected static synchronized <T> Sender<T> createSender(
            String serverName, String moduleName, String channel)
    {
        return new Sender<T>(serverName, moduleName, channel);
    }

    protected static Coordinator createCoordinator(String serverName, String moduleName) {
        return new Coordinator(serverName, moduleName);
    }

    static class Coordinator implements MessagePassing.Coordinator {

        private String serverName;
        private String moduleName;

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

        public <T> Receiver<T> getReceiver(String channel, Class<T> cls) {
            return MessagePasser.createReceiver(serverName, moduleName, channel, cls);
        }

        public <T> Receiver<T> getReceiver(Class<T> cls) {
            return getReceiver("", cls);
        }
    }

    static class Sender<T> implements MessagePassing.Sender<T> {

        private String serverName;
        private String moduleName;
        private String channel;

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
        private Class<T> cls;
        private ReadWriteLock rwLock;

        public Receiver(Class<T> cls) {
            this.cls = cls;
            rwLock = new ReentrantReadWriteLock();
        }

        public void setHandler(Consumer<T> handler) {
            Lock lock = rwLock.writeLock();
            lock.lock();

            this.handler = handler;

            lock.unlock();
        }

        @SuppressWarnings("unchecked")
        private static <M> M convertMessage(Object message, Class<M> cls) throws Exception {
            Class<?> msgCls = message.getClass();

            if (msgCls.isRecord() && cls.isRecord()) {
                // Special handling for equivalent record classes, i.e. records
                // which might be different classes, but whose members have the
                // same types so we can convert easily.

                RecordComponent[] msgComponents = msgCls.getRecordComponents();
                RecordComponent[] components = cls.getRecordComponents();
                Object[] args = new Object[components.length];

                Class<?>[] paramTypes =
                    Arrays.stream(components)
                    .map(RecordComponent::getType)
                    .toArray(Class<?>[]::new);
                Constructor<M> constructor = cls.getDeclaredConstructor(paramTypes);

                for (int i = 0; i < args.length; i++) {
                    args[i] = convertMessage(
                            msgComponents[i].getAccessor().invoke(message),
                            paramTypes[i]);
                }

                return constructor.newInstance(args);
            } else if (msgCls.isArray() && cls.isArray()) {
                // Special handling for arrays

                Object[] msgArr = (Object[]) message;
                Object[] arr = new Object[msgArr.length];
                Class<?> componentType = msgCls.componentType();
                Class<? extends Object[]> arrCls = (Class<? extends Object[]>) cls;

                for (int i = 0; i < msgArr.length; i++) {
                    arr[i] = convertMessage(msgArr[i], componentType);
                }

                return cls.cast(Arrays.copyOf(arr, arr.length, arrCls));
            } else if (msgCls.isEnum() && cls.isEnum()) {
                // Special handling for enums

                Enum<?> msgEnum = (Enum) message;
                Class<? extends Enum> enumCls = (Class<? extends Enum>) cls;

                return cls.cast(Enum.valueOf(enumCls, msgEnum.name()));
            } else {
                return cls.cast(message);
            }
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
