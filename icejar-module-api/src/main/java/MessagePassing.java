package Icejar;


public final class MessagePassing {

    private MessagePassing() {}

    public static interface Coordinator {
        <T> Sender<T> getSender(String moduleName, Class<T> cls);
        <T> Sender<T> getSender(String moduleName, String channel, Class<T> cls);
        <T> Receiver<T> getReceiver(Class<T> cls);
        <T> Receiver<T> getReceiver(String channel, Class<T> cls);
    }

    public static interface Sender<T> {
        void send(T message);
    }

    public static interface Receiver<T> {
        T recv();
    }
}
