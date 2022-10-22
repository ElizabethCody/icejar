package icejar;

import java.util.function.Consumer;


/**
 * Utility class containing the interfaces of the message passing API for
 * Modules.
 * <p>
 * Modules which implement the setupMessagePassing method will be given an
 * instance of Coordinator from which Senders and Receivers can be obtained.
 * <p>
 * A Sender sends messages to other Module instances while Receiver receives
 * incoming messages from Senders.
 * <p>
 * All message passing is done between Module instances for the <i>same</i>
 * Mumble server, i.e. a message will be received by an instance of Module
 * which shares a Mumble server with the sender.
 * <p>
 * The motivating use-case behind this API is to enable shared functionality
 * between Modules which would otherwise lead to conflicts if the functionality
 * were implemented directly in the Modules themselves.
 *
 * For example: multiple Modules might want to make changes to users' names,
 * but this could cause problems if they overwrite each others' changes.
 * It might make sense to break the handling of names into its own Module
 * which can prevent conflicting changes. The Modules which would otherwise
 * set users' names directly can now pass a message to the dedicated
 * name-changing Module which contains the changes to make.
 * <p>
 * The message passing API is only designed to facilitate one-way interactions
 * rather than bi-directional communication between Modules. The intended usage
 * of the API is only for Modules which provide some re-usable functionality to
 * accept messages from Modules which use this functionality.
 *
 * Tight coupling of/cyclic dependencies between Modules are discouraged, since
 * Icejar makes no guarantees about which Modules are loaded at any given time.
 * If you have Modules which absolutely <i>must</i> be loaded at the same time
 * as each other and which <i>must</i> communicate, consider combining them
 * into a single Module.
 * <hr/>
 * Message passing is organized as follows:
 * <p>
 * Each Module can open any number of Receivers. Each Receiver listens on a
 * its own "channel" for that Module. If a Receiver is obtained for a channel
 * which is already in use, the new Receiver will replace the old one which
 * will no longer receive any new messages.
 * <p>
 * Each Receiver is created with a type into which incoming messages must be
 * converted in order to be handled by that Receiver. There is special handling
 * for record classes: if a record is sent to a Receiver which expects records,
 * as long as the fields of the two records classes are the same, the message
 * will be converted into an instance of the class expected by the Receiver,
 * even if the actual classes differ.
 * <p>
 * A "channel" is simply the means by which the Receivers of a Module are
 * differentiated from one another. In order to receive messages of different
 * types, a Module can open a Receiver for each type on its own channel.
 * Calls to `getSender()` and `getReceiver()` without specifying a channel
 * implicitly use the channel named "" (empty string).
 * <p>
 * Each Sender is created with the name of Module to which it sends messages
 * and the channel over which the messages are sent. Any number of Senders can
 * be created for a single Receiver.
 *
 * @see Module#setupMessagePassing
 * @see Record
 */
public final class MessagePassing {

    private MessagePassing() {}

    /**
     * Grants Modules access to Senders and Receivers.
     */
    public static interface Coordinator {
        /**
         * Get a Sender to send messages to the module with the given name via
         * the default channel.
         */
        <T> Sender<T> getSender(String moduleName);

        /**
         * Get a Sender to send messages to the module with the given name via
         * the given channel.
         */
        <T> Sender<T> getSender(String moduleName, String channel);

        /**
         * Get a Receiver for the current module which receives messages of
         * type `cls` via the default channel.
         */
        <T> Receiver<T> getReceiver(Class<T> cls);

        /**
         * Get a Receiver for the current module which receivers messages of
         * type `cls` via the given channel.
         */
        <T> Receiver<T> getReceiver(String channel, Class<T> cls);
    }

    /**
     * Sends messages.
     */
    public static interface Sender<T> {
        /**
         * Send a message to a Receiver.
         *
         * @return Whether or not the message was sent successfully. This can
         * fail if the message queue for the Receiver is full, if the
         * message's type is incompatible with the Receiver to which it was
         * sent, or if there exists no corresponding Receiver for this Sender.
         */
        boolean send(T message);
    }

    /**
     * Receives messages.
     */
    public static interface Receiver<T> {
        /**
         * Receive a message from a Sender. This method waits indefinitely
         * until a message is available.
         *
         * @throws InterruptedException if waiting for a message was interrupted.
         */
        T recv() throws InterruptedException;

        /**
         * Receive a message from a Sender. This message waits for the given
         * number of milliseconds for a message and returns `null` if no
         * message is available.
         *
         * @throws InterruptedException if waiting for a message was interrupted.
         */
        T recv(long timeoutMillis) throws InterruptedException;

        /**
         * Receive a message from a Sender. This message does not wait and
         * immediately returns the first available message or `null` if no
         * message is available.
         */
        T recvNonBlocking();

        /**
         * Set a method which will handle incoming messages. If a handler is
         * set, it takes priority over enqueuing messages, i.e. messages will
         * be passed to the handler rather than enqueued to be received by one
         * of the `recv` methods.
         */
        void setHandler(Consumer<T> handler);
    }
}
