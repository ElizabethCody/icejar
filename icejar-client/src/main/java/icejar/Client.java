package icejar;

import java.io.File;
import java.util.Collections;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.logging.Logger;
import java.util.logging.Level;

import com.moandjiezana.toml.Toml;
import com.zeroc.Ice.*;
import MumbleServer.*;


// Ice client for a single virtual mumble server
final class Client {
    private static final String ICE_CONTEXT_SECRET_VAR = "secret";
    private static final String SERVER_NAME_VAR = "registerName";

    private final Logger logger;

    private static final int MIN_RECONNECT_DELAY = 1000;
    private static final int MAX_RECONNECT_DELAY = 60000;

    private String[] iceArgs;
    private String iceHost;
    private int icePort;
    private String callbackHost;
    private int callbackPort;

    private String serverName;
    private Long serverID;
    private Toml config;

    private Map<File, Module> enabledModules = new HashMap<>();

    private Communicator communicator;
    private ObjectAdapter adapter;
    private MetaPrx meta;
    private ServerPrx server;

    private Thread connectThread;


    Client(Logger logger) {
        this.logger = logger;
    }

    synchronized void reconfigure(
            String[] iceArgs, String iceHost, int icePort, String iceSecret,
            String callbackHost, int callbackPort,
            Map<File, Module> enabledModules, String serverName, Long serverID,
            Toml config) {
        this.iceArgs = iceArgs;
        this.iceHost = iceHost;
        this.icePort = icePort;
        this.callbackHost = callbackHost;
        this.callbackPort = callbackPort;

        this.serverName = serverName;
        this.serverID = serverID;
        this.config = config;

        // Set up the communicator
        if (communicator == null || communicator.isShutdown()) {
            Properties properties = Util.createProperties();
            properties.setProperty("Ice.ImplicitContext", "Shared");
            properties.setProperty("Ice.ThreadPool.Client.SizeMax", "1");
            properties.setProperty("Ice.ThreadPool.Server.SizeMax", "1");

            InitializationData initData = new InitializationData();
            initData.properties = properties;

            communicator = Util.initialize(initData);
        }

        ImplicitContext iceContext = communicator.getImplicitContext();
        iceContext.put(
                ICE_CONTEXT_SECRET_VAR,
                Optional.ofNullable(iceSecret).orElse(""));

        // Unload modules which are no longer enabled
        if (this.enabledModules != null) {
            for (File module: this.enabledModules.keySet()) {
                if (!enabledModules.containsKey(module)) {
                    unloadModule(module);
                }
            }
        }
        this.enabledModules = enabledModules;

        startReconnectThread();
    }

    boolean hasModuleFile(File moduleFile) {
        return enabledModules.containsKey(moduleFile);
    }

    Set<File> getEnabledModules() {
        return Collections.unmodifiableSet(enabledModules.keySet());
    }

    public Module getEnabledModule(File moduleFile) {
        return enabledModules.get(moduleFile);
    }

    private synchronized void startReconnectThread() {
        if (connectThread != null) {
            connectThread.interrupt();
            try {
                connectThread.join();
            } catch (InterruptedException e) {
                logger.log(Level.WARNING, "Waiting for previous connection thread to exit threw: " + e);
                return;
            }
        }

        connectThread = new Thread(() -> {
            try {
                this.reconnect();
            } catch (java.lang.Exception e) {
                logger.log(Level.WARNING, "Connection thread threw: " + e);
            }
        });

        connectThread.start();
    }

    private void reconnect() throws java.lang.Exception {
        logger.fine("Re-connecting...");

        int reconnectDelay = MIN_RECONNECT_DELAY;

        while (!Thread.interrupted()) {
            try {
                synchronized (this) {
                    disconnect();
                    attemptConnection();
                    setup();
                }
                break;
            } catch (OperationInterruptedException e) {
                break;
            } catch (java.lang.Exception e) {
                logger.log(Level.FINE, "Connection attempt threw: " + e);

                if (reconnectDelay < MAX_RECONNECT_DELAY) {
                    Thread.sleep(reconnectDelay);
                    reconnectDelay *= 2;
                } else {
                    Thread.sleep(MAX_RECONNECT_DELAY);
                }
            }
        }
    }

    private void attemptConnection() throws java.lang.Exception {
        String proxyString = String.format("Meta:default -h %s -p %d ", iceHost, icePort);
        proxyString = proxyString + String.join(" ", iceArgs);
        meta = MetaPrx.checkedCast(communicator.stringToProxy(proxyString));

        // NOTE: Mumble re-named its Ice module from "Murmur" to "MumbleServer"
        // If you try to connect to a Mumble server which was built against the
        // old Ice module with a build of icejar which uses the new module or
        // vice-versa, the call to `MetaPrx.checkedCast` will return `null`.

        String adapterString = String.format("tcp -h %s", callbackHost);
        if (callbackPort >= 0) {
            adapterString += String.format(" -p %d", callbackPort);
        }
        adapter = communicator.createObjectAdapterWithEndpoints("Callback.Client", adapterString);
        adapter.activate();

        // Set Active Connection Management (ACM) parameters
        meta.ice_getConnection().setACM(
                OptionalInt.of(120),
                Optional.of(ACMClose.CloseOnIdle),
                Optional.of(ACMHeartbeat.HeartbeatOnIdle));

        // Try to reconnect if the remote mumble server drops the connection.
        setAutoReconnectEnabled(true);

        obtainServerPrx();

        logger.info("Connected.");
    }

    private void disconnect() {
        // When we explicitly disconnect, we don't want to trigger an automatic
        // reconnect attempt.
        setAutoReconnectEnabled(false);
        if (meta != null) {
            meta.ice_getConnection().close(ConnectionClose.Gracefully);
            meta = null;
            logger.info("Disconnected.");
        }

        if (adapter != null) {
            adapter.destroy();
            adapter = null;
        }
    }

    private void obtainServerPrx() throws java.lang.Exception {
        if (serverID != null) {
            ServerPrx server = meta.getServer(serverID.intValue());
            if (server == null) {
                String errorMsg = String.format("No server with ID %d", serverID);
                throw new java.lang.Exception(errorMsg);
            } else if (serverName != null) {
                String actualServerName = server.getConf(SERVER_NAME_VAR);
                if (!serverName.equals(actualServerName)) {
                    String errorMsg = String.format("Server with ID `%d` is named \"%s\" instead of \"%s\"",
                            serverID, actualServerName, serverName);
                    throw new java.lang.Exception(errorMsg);
                }
            }
            this.server = server;
        } else if (serverName != null) {
            for (ServerPrx server: meta.getAllServers()) {
                if (serverName.equals(server.getConf(SERVER_NAME_VAR))) {
                    this.server = server;
                    break;
                }
            }
        } else {
            this.server = null;
        }
    }

    synchronized void reloadModules(Map<File, Module> changedModules) {
        for (Map.Entry<File, Module> changedModuleEntry: changedModules.entrySet()) {
            File changedModuleFile = changedModuleEntry.getKey();
            Module changedModule = changedModuleEntry.getValue();

            logger.fine(String.format("Reloading `%s`", changedModuleFile));

            unloadModule(changedModuleFile);
            enabledModules.put(changedModuleFile, changedModule);
        }

        startReconnectThread();
    }

    private void unloadModule(File moduleFile) {
        if (enabledModules.containsKey(moduleFile)) {
            Module module = enabledModules.get(moduleFile);
            if (module != null) {
                try {
                    module.cleanup();
                } catch (java.lang.Exception e) {
                    logger.log(Level.WARNING, "Call to `cleanup()` for `Module` from `" + moduleFile + "` threw:", e);
                }
            }

            enabledModules.put(moduleFile, null);
        }
    }

    private synchronized void setup() {
        for (Map.Entry<File, Module> moduleEntry: enabledModules.entrySet()) {
            File moduleFile = moduleEntry.getKey();
            String moduleFileName = moduleFile.getName();
            String moduleName = moduleFileName;

            int moduleNameEndIndex = moduleFileName.lastIndexOf('.');
            if (moduleNameEndIndex != -1) {
                moduleName = moduleFileName.substring(0, moduleNameEndIndex);
            }

            Module module = moduleEntry.getValue();

            if (module != null) {
                try {
                    Toml moduleTable = config.getTable(moduleName);
                    Map<String, java.lang.Object> moduleConfig;
                    if (moduleTable != null) {
                        moduleConfig = moduleTable.toMap();
                    } else {
                        moduleConfig = new HashMap<>();
                    }


                    module.setup(moduleConfig, meta, adapter, server);
                } catch (java.lang.Exception e) {
                    logger.log(Level.WARNING, "Call to `setup()` for `Module` from `" + moduleFile + "` threw: " + e);
                }
            }
        }
    }

    synchronized void cleanup() {
        logger.fine("Cleaning up.");

        for (File moduleFile: enabledModules.keySet()) {
            unloadModule(moduleFile);
        }

        disconnect();
        communicator.destroy();
    }

    private void setAutoReconnectEnabled(boolean isEnabled) {
        if (meta != null) {
            Connection connection = meta.ice_getConnection();

            if (isEnabled) {
                connection.setCloseCallback(closed -> startReconnectThread());
            } else {
                connection.setCloseCallback(closed -> {});
            }
        }
    }
}
