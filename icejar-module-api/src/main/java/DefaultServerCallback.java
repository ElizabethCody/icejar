package icejar;

import Murmur.*;
import com.zeroc.Ice.Current;

/** This interface is provided to allow the implementation of Ice callbacks
 * without having to define an implementation for every event.
 * <p>
 * Each event type has both a variant which does and does not throw an
 * exception. If an exception-throwing method is implemented, then its non
 * exception-throwing equivalent <b>must not</b> also be implemeted.
 * <p>
 * See <code>com.zeroc.Ice.ServerCallback</code> for documentation.
 */
public interface DefaultServerCallback extends ServerCallback {
    @Override
    default void userTextMessage(User state, TextMessage message, Current current) {
        try {
            userTextMessageThrowsException(state, message, current);
        } catch (Exception e) {}
    }
    default void userTextMessageThrowsException(
            User state, TextMessage message, Current current) throws Exception {}

    @Override
    default void channelCreated(Channel state, Current current) {
        try {
            channelCreatedThrowsException(state, current);
        } catch (Exception e) {}
    }
    default void channelCreatedThrowsException(
            Channel state, Current current) throws Exception {}

    @Override
    default void channelRemoved(Channel state, Current current) {
        try {
            channelRemovedThrowsException(state, current);
        } catch (Exception e) {}
    }
    default void channelRemovedThrowsException(
            Channel state, Current current) throws Exception {}

    @Override
    default void channelStateChanged(Channel state, Current current) {
        try {
            channelStateChangedThrowsException(state, current);
        } catch (Exception e) {}
    }
    default void channelStateChangedThrowsException(
            Channel state, Current current) throws Exception {}

    @Override
    default void userConnected(User state, Current current) {
        try {
            userConnectedThrowsException(state, current);
        } catch (Exception e) {}
    }
    default void userConnectedThrowsException(
            User state, Current current) throws Exception {}

    @Override
    default void userDisconnected(User state, Current current) {
        try {
            userDisconnectedThrowsException(state, current);
        } catch (Exception e) {}
    }
    default void userDisconnectedThrowsException(
            User state, Current current) throws Exception {}

    @Override
    default void userStateChanged(User state, Current current) {
        try {
            userStateChangedThrowsException(state, current);
        } catch (Exception e) {}
    }
    default void userStateChangedThrowsException(
            User state, Current current) throws Exception {}
}
