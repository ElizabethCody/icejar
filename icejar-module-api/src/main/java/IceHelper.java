package icejar;

import com.zeroc.Ice.ObjectAdapter;
import Murmur.*;

public final class IceHelper {
    private IceHelper() {}

    /**
     * Helper function to add a callback to a Mumble server.
     * 
     * @param server Interface to the specific virtual server to which the
     * callback will be added.
     * @param adapter Interface to create callback objects.
     * @param callback Object implementing the ServerCallback interface.
     *
     * @return The ServerCallbackPrx object which can be used to un-register
     * the callback.
     */
    public static ServerCallbackPrx addServerCallback(
            ServerPrx server, ObjectAdapter adapter,
            ServerCallback callback) throws Exception
    {
        ServerCallbackPrx cb = ServerCallbackPrx.uncheckedCast(
                adapter.addWithUUID(callback));

        server.addCallback(cb);
        return cb;
    }

    /**
     * Helper function to add a context action callback to a Mumble server.
     * 
     * @param server Interface to the specific virtual server to which the
     * callback will be added.
     * @param adapter Interface to create callback objects.
     * @param session The session ID of the user to which the action is added.
     * @param action Unique internal name for the action. This name identifies
     * the action to the given callback
     * @param text External name of the action. This is the text label for the
     * action as displayed in the user's Mumble client.
     * @param callback Object implementing the ServerContextCallback interface.
     *
     * @return The ServerContextCallbackPrx object which can be used to
     * un-register the callback.
     */
    public static ServerContextCallbackPrx addServerContextCallback(
            ServerPrx server, ObjectAdapter adapter,
            int session, String action, String text,
            ServerContextCallback callback, int ctx) throws Exception
    {
        ServerContextCallbackPrx cb = ServerContextCallbackPrx.uncheckedCast(
                adapter.addWithUUID(callback));

        server.addContextCallback(session, action, text, cb, ctx);
        return cb;
    }

    /**
     * Helper function to send a new message to the same destination as an
     * existing message.
     * <p>
     * This is useful in the implementation of features which respond to text
     * messages from users.
     *
     * @param server Interface to the specific virtual server to which the
     * text message will be sent.
     * @param message Text message from which the destination for the new
     * message will be copied.
     */
    public static void sendMessageSameDestination(
            ServerPrx server, TextMessage message,
            String messageString) throws Exception
    {
        for (int session: message.sessions) {
            server.sendMessage(session, messageString);
        }
        for (int channel: message.channels) {
            server.sendMessageChannel(channel, false, messageString);
        }
        for (int tree: message.trees) {
            server.sendMessageChannel(tree, true, messageString);
        }
    }
}
