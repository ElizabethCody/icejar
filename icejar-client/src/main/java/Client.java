package icejar;

import java.io.File;
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
public final class Client {
    private static final String ICE_CONTEXT_SECRET_VAR = "secret";
    private static final String SERVER_NAME_VAR = "registerName";

    private Logger logger;

    private File configFile;

    private static final int MIN_RECONNECT_DELAY = 1000;
    private static final int MAX_RECONNECT_DELAY = 60000;
    private int reconnectDelay = MIN_RECONNECT_DELAY;

    private String iceHost;
    private int icePort;
    private String[] iceArgs;

    private String serverName;
    private Long serverID;
    private Toml config;

    private Map<File, Module> enabledModules = new HashMap<File, Module>();

    private Communicator communicator;
    private ObjectAdapter adapter;
    private MetaPrx meta;
    private ServerPrx server;

    private Thread connectThread;


    protected Client(File configFile, Logger logger) {
        this.configFile = configFile;
        this.logger = logger;
    }

    protected void reconfigure(
            String[] iceArgs, String iceHost, int icePort, String iceSecret,
            Map<File, Module> enabledModules, String serverName, Long serverID,
            Toml config) throws java.lang.Exception
    {
        this.iceHost = iceHost;
        this.icePort = icePort;
        this.iceArgs = iceArgs;

        this.serverName = serverName;
        this.serverID = serverID;
        this.config = config;

        // Set up the communicator
        if (communicator == null || communicator.isShutdown()) {
            Properties properties = Util.createProperties();
            properties.setProperty("Ice.ImplicitContext", "Shared");

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

    protected boolean hasModuleFile(File moduleFile) {
        return enabledModules.containsKey(moduleFile);
    }

    private synchronized void startReconnectThread() {
        if (connectThread != null) {
            connectThread.interrupt();
            try {
                connectThread.join();
            } catch (InterruptedException e) {
                logger.log(Level.FINE, "Waiting for previous connection thread of `Client` for `" + configFile + "` to exit threw:", e);
                return;
            }
        }

        connectThread = new Thread(() -> {
            try {
                this.reconnect();
            } catch (java.lang.Exception e) {
                logger.log(Level.WARNING, "Connection thread of `Client` for `" + configFile + "` threw:", e);
            }
        });

        connectThread.start();
    }

    private void reconnect() throws java.lang.Exception {
        logger.info(String.format("Connection attempt of `Client` for `%s` started.", configFile));

        reconnectDelay = MIN_RECONNECT_DELAY;

        while (!Thread.interrupted()) {
            try {
                disconnect();
                attemptConnection();
                setup();
                break;
            } catch (OperationInterruptedException e) {
                break;
            } catch (java.lang.Exception e) {
                logger.log(Level.FINE, "Connection attempt of `Client` for `" + configFile + "` threw:" + e);

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
        String proxyString = String.format("Meta:default -h %s -p %d", iceHost, icePort);
        meta = MetaPrx.checkedCast(communicator.stringToProxy(proxyString));

        // NOTE: Mumble re-named its Ice module from "Murmur" to "MumbleServer"
        // If you try to connect to a Mumble server which was built against the
        // old Ice module with a build of icejar which uses the new module or
        // vice-versa, the call to `MetaPrx.checkedCast` will return `null`.

        String adapterString = String.format("tcp -h %s", iceHost);
        adapter = communicator.createObjectAdapterWithEndpoints("Client.Callback", adapterString);
        adapter.activate();

        // Set Active Connection Management (ACM) parameters
        meta.ice_getConnection().setACM(
                OptionalInt.of(120),
                Optional.of(ACMClose.CloseOnIdle),
                Optional.of(ACMHeartbeat.HeartbeatOnIdle));

        // Try to reconnect if the remote mumble server drops the connection.
        setAutoReconnectEnabled(true);

        getServerPrx();

        logger.info(String.format("`Client` for `%s` connected.", configFile));
    }

    private void disconnect() {
        // When we explicitly disconnect, we don't want to trigger an automatic
        // reconnect attempt.
        setAutoReconnectEnabled(false);
        if (meta != null) {
            meta.ice_getConnection().close(ConnectionClose.Gracefully);
        }

        if (adapter != null) {
            adapter.destroy();
            adapter = null;
        }
    }

    private void getServerPrx() throws java.lang.Exception {
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

    protected void reloadModules(Map<File, Module> changedModules) {
        for (Map.Entry<File, Module> changedModuleEntry: changedModules.entrySet()) {
            File changedModuleFile = changedModuleEntry.getKey();
            Module changedModule = changedModuleEntry.getValue();

            logger.fine(String.format("Reloading `%s` for `%s`", changedModuleFile, configFile));

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
                    synchronized(module) {
                        module.cleanup();
                    }
                } catch (java.lang.Exception e) {
                    logger.log(Level.WARNING, "Call to `cleanup()` for `Module` from `" + moduleFile + "` threw:", e);
                }
            }

            enabledModules.put(moduleFile, null);
        }
    }

    private void setup() {
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
                        moduleConfig = new HashMap<String, java.lang.Object>();
                    }

                    synchronized(module) {
                        module.setup(moduleConfig, meta, adapter, server);
                    }
                } catch (java.lang.Exception e) {
                    logger.log(Level.WARNING, "Call to `setup()` for `Module` from `" + moduleFile + "` threw:", e);
                }
            }
        }
    }

    protected void cleanup() {
        logger.fine(String.format("Cleaning up `Client` for `%s`", configFile));

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
