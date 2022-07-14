package icejar;

import java.util.Optional;
import java.util.Map;

import com.zeroc.Ice.ObjectAdapter;
import Murmur.*;

/**
 * Modules must be <code>.jar</code> files containing a class named "Module"
 * at the top level which extends this abstract class.
 * <p>
 * Implementations of Module must provide the default contstructor, i.e.
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
            Optional<ServerPrx> server) throws Exception;

    /**
     * Clean up a Module when it is unloaded.
     * <p>
     * This method should be overridden if your Module requires any extra
     * cleanup. Callbacks registered with Ice are removed automatically and do
     * not require any implementation of this method.
     */
    default void cleanup() throws Exception {}
}
