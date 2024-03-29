package icejar;

import java.util.Map;
import java.util.logging.Logger;
import java.sql.Connection;

import com.zeroc.Ice.ObjectAdapter;
import MumbleServer.*;

/**
 * Main interface implemeted by Icejar Modules.
 *
 * Modules must be <code>.jar</code> files containing an implementor of the
 * `Module` interface. Only a single implementor of `Module` will be instanced
 * from each <code>.jar</code> file by IceJar.
 * <p>
 * Implementations of `Module` must provide the default contstructor, i.e.
 * <code>new Module()</code> must be a valid way of creating an instance.
 * <p>
 * Be aware that instances of Module will be accessed from different threads:
 * setup and cleanup will be initiated by the main class, but any functionality
 * you register as a callback using the Ice interfaces will be spawned on a
 * thread pool managed by Ice. Make sure that a call to <code>setup()</code> or
 * <code>cleanup()</code> is thread-safe given that other logic from your
 * implementation may be executing in a different thread.
 */
public interface Module {
    /**
     * Set up a Module when a connection is established.
     * <p>
     * Be aware that the connection to the server will be closed and re-opened
     * when modules for a Client are reloaded. Even if a specific Module was not
     * reloaded, its connection will still be interrupted and its
     * <code>setup()</code> method will be called. <code>setup()</code> should
     * be implemented with the knowledge that it may be called several times.
     *
     * @param config The parsed configuration for the Client to which this
     * Module instance belongs.
     * @param meta Interface to the Mumble server.
     * @param adapter Interface to create callback objects.
     * @param server Interface to the specific virtual server for this Module.
     */
    abstract void setup(
            Map<String, Object> config, MetaPrx meta, ObjectAdapter adapter,
            ServerPrx server) throws Exception;

    /**
     * Clean up a Module when it is unloaded.
     * <p>
     * This method should be overridden if your Module requires any extra
     * cleanup. Callbacks registered with Ice are removed automatically and do
     * not require any implementation of this method.
     */
    default void cleanup() throws Exception {}

    /**
     * Receive Logger instance for this module.
     * <p>
     * If your Module needs to log messages, instead of acquiring a Logger
     * itself, it is preferrable to override this method and use the Logger
     * passed in.
     * <p>
     * This method is called only once. It is called immediately after the
     * Module is instatitated and before any call to <code>setup()</code> or
     * <code>cleanup()</code>.
     *
     * @param logger The given logger for this Module.
     */
    default void setLogger(Logger logger) {}

    /**
     * Receive message passing coordinator for this module.
     * <p>
     * If your Module needs to communicate with other modules, Icejar provides
     * a simple message passing API. The Coordinator object is used to get
     * instances of Sender and Receiver, from which messages can be sent and
     * received.
     * <p>
     * This method is called only once. It is called immediately after the
     * Module is instatiated, before any call to <code>setup()</code> or
     * <code>cleanup()</code>, and after the call to <code>setLogger()</code>.
     *
     * @param coordinator The message passing coordinator for this Module.
     *
     * @see MessagePassing
     */
    default void setupMessagePassing(MessagePassing.Coordinator coordinator) {}

    /**
     * Receive database connection for this module.
     * <p>
     * This method is only called once. It is called immediately after the
     * Module is instantiated, before any call to <code>setup()</code> or
     * <code>cleanup()</code>, and after the call to
     * <code>setupMessagePassing()</code>.
     * <p>
     * This method <i>will not</i> be called if opening the database connection
     * threw an execption.
     * <p>
     * The database connection will be closed automatically when the Module
     * is un-loaded.
     *
     * @param c The database connection.
     */
    default void setDatabaseConnection(Connection c) {
        try {
            c.close();
        } catch (Exception ignored) {}
    }
}
