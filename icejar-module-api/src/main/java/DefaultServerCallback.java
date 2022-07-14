package icejar;

import Murmur.*;
import com.zeroc.Ice.Current;

/** This interface is provided to allow the implementation of Ice callbacks
 * without having to define an implementation for every event.
 * <p>
 * See <code>com.zeroc.Ice.ServerCallback</code> for documentation.
 */
public interface DefaultServerCallback extends ServerCallback {
        @Override
        default void userTextMessage(User state, TextMessage message, Current current) {}
        @Override
        default void channelCreated(Channel state, Current current) {}
        @Override
        default void channelRemoved(Channel state, Current current) {}
        @Override
        default void channelStateChanged(Channel state, Current current) {}
        @Override
        default void userConnected(User state, Current current) {}
        @Override
        default void userDisconnected(User state, Current current) {}
        @Override
        default void userStateChanged(User state, com.zeroc.Ice.Current current) {}
}
