package icejar;

import static icejar.ClientManager.*;

import java.io.File;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;

import com.moandjiezana.toml.Toml;
import com.zeroc.Ice.*;
import Murmur.*;


// Ice client for a single virtual mumble server
public final class Client {
    private final String ICE_PROXY_STRING_PREFIX = "Meta:default ";
    private final String ICE_CONTEXT_SECRET_VAR = "secret";
    private final String SERVER_NAME_VAR = "registerName";

    private File configFile;

    private final int MIN_RECONNECT_DELAY = 1000;
    private final int MAX_RECONNECT_DELAY = 60000;
    private int reconnectDelay = MIN_RECONNECT_DELAY;

    private String iceProxyString;
    private String[] iceArgs;

    private Optional<String> serverName = Optional.empty();
    private Optional<Long> serverID = Optional.empty();
    private volatile Toml config;

    private Map<File, Module> enabledModules = new HashMap<File, Module>();

    private Communicator communicator;
    private volatile MetaPrx meta;
    private volatile Optional<ServerPrx> server;

    private Thread connectThread;


    protected Client(
            File configFile, String[] iceArgs, String iceProxyString,
            Optional<String> iceSecret, Map<File, Module> enabledModules,
            Optional<String> serverName, Optional<Long> serverID,
            Toml config) throws java.lang.Exception
    {
        StringBuilder statusMsg = new StringBuilder()
            .append("Instancing Client for `")
            .append(configFile)
            .append("`");
        System.out.println(statusMsg);

        this.iceProxyString = ICE_PROXY_STRING_PREFIX + iceProxyString;
        this.iceArgs = iceArgs;

        this.configFile = configFile;
        this.serverName = serverName;
        this.serverID = serverID;
        this.config = config;
        this.enabledModules = enabledModules;

        Properties properties = Util.createProperties(iceArgs);
        properties.setProperty("Ice.ImplicitContext", "Shared");

        InitializationData initData = new InitializationData();
        initData.properties = properties;

        communicator = Util.initialize(initData);

        if (iceSecret.isPresent()) {
            ImplicitContext iceContext = communicator.getImplicitContext();
            iceContext.put(ICE_CONTEXT_SECRET_VAR, iceSecret.get());
        }

        startReconnectThread();
    }

    protected boolean hasModuleFile(File moduleFile) {
        return enabledModules.containsKey(moduleFile);
    }

    private synchronized void startReconnectThread() {
        connectThread = new Thread(this::reconnect);
        connectThread.start();
    }

    private void reconnect() {
        StringBuilder statusMsg = new StringBuilder()
            .append("Connection attempt of Client for `")
            .append(configFile)
            .append("` started");
        System.out.println(statusMsg);

        reconnectDelay = MIN_RECONNECT_DELAY;

        while (true) {
            try {
                connect();
                setup();
                return;
            } catch (java.lang.Exception e) {
                ErrorHelper.printException("Connection attempt of Client for", configFile, e);

                if (e instanceof CommunicatorDestroyedException) {
                    return;
                }

                try {
                    if (reconnectDelay < MAX_RECONNECT_DELAY) {
                        Thread.sleep(reconnectDelay);
                        reconnectDelay *= 2;
                    } else {
                        Thread.sleep(MAX_RECONNECT_DELAY);
                    }
                } catch (InterruptedException e2) {
                    ErrorHelper.printException("Connection thread of Client for", configFile, e2);
                    return;
                }
            }
        }
    }

    private void connect() throws java.lang.Exception {
        meta = MetaPrx.checkedCast(communicator.stringToProxy(iceProxyString));

        Connection connection = meta.ice_getConnection();
        // Set active connection management parameters
        connection.setACM(
                OptionalInt.of(120),
                Optional.of(ACMClose.CloseOnIdle),
                Optional.of(ACMHeartbeat.HeartbeatOnIdle));
        // Try to reconnect if the connection closes
        connection.setCloseCallback(closed -> startReconnectThread());

        getServerPrx();

        StringBuilder statusMsg = new StringBuilder()
            .append("Client for `")
            .append(configFile)
            .append("` connected");
        System.out.println(statusMsg);
    }

    private void getServerPrx() throws java.lang.Exception {
        if (serverID.isPresent()) {
            ServerPrx server = meta.getServer(serverID.get().intValue());
            if (server == null) {
                StringBuilder errorMsg = new StringBuilder()
                    .append("No server with ID ")
                    .append(serverID.get());
                throw new java.lang.Exception(errorMsg.toString());
            } else if (serverName.isPresent()) {
                String actualServerName = server.getConf(SERVER_NAME_VAR);
                if (!serverName.get().equals(actualServerName)) {
                    StringBuilder errorMsg = new StringBuilder()
                        .append("Server with ID ")
                        .append(serverID.get())
                        .append(" is named \"")
                        .append(actualServerName)
                        .append("\" instead of \"")
                        .append(serverName.get())
                        .append("\"");
                    throw new java.lang.Exception(errorMsg.toString());
                }
            }
            this.server = Optional.of(server);
        } else if (serverName.isPresent()) {
            for (ServerPrx server: meta.getAllServers()) {
                if (serverName.get().equals(server.getConf(SERVER_NAME_VAR))) {
                    this.server = Optional.of(server);
                    break;
                }
            }
        } else {
            this.server = Optional.empty();
        }
    }

    protected void reloadModules(Map<File, Module> changedModules) {
        for (Map.Entry<File, Module> changedModuleEntry: changedModules.entrySet()) {
            File changedModuleFile = changedModuleEntry.getKey();
            Module changedModule = changedModuleEntry.getValue();

            StringBuilder statusMsg = new StringBuilder()
                .append("Reloading `")
                .append(changedModuleFile)
                .append("` for `")
                .append(configFile)
                .append("`");
            System.out.println(statusMsg);

            unloadModule(changedModuleFile);
            enabledModules.put(changedModuleFile, changedModule);
        }

        unsetCloseCallback();
        meta.ice_getConnection().close(ConnectionClose.Forcefully);
        connectThread.interrupt();

        startReconnectThread();
    }

    private void unloadModule(File moduleFile) {
        if (enabledModules.containsKey(moduleFile)) {
            Module module = enabledModules.get(moduleFile);
            if (module != null) {
                try {
                    module.synchronizedCleanup();
                } catch (java.lang.Exception e) {
                    ErrorHelper.printException("`cleanup()` for `Module` from", moduleFile, e);
                }
            }

            enabledModules.put(moduleFile, null);
        }
    }

    private synchronized void setup() {
        for (Map.Entry<File, Module> moduleEntry: enabledModules.entrySet()) {
            File moduleFile = moduleEntry.getKey();
            String moduleFileName = moduleFile.getName();
            int moduleNameEndIndex = moduleFileName.length() - MODULE_EXTENSION.length();
            String moduleName = moduleFileName.substring(0, moduleNameEndIndex);
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

                    module.synchronizedSetup(moduleConfig, meta, server);
                } catch (java.lang.Exception e) {
                    ErrorHelper.printException("`setup()` for `Module` from", moduleFile, e);
                }
            }
        }
    }

    protected void cleanup() {
        StringBuilder statusMsg = new StringBuilder()
            .append("Cleaning up Client for `")
            .append(configFile)
            .append("`");
        System.out.println(statusMsg);

        for (File moduleFile: enabledModules.keySet()) {
            unloadModule(moduleFile);
        }

        unsetCloseCallback();

        connectThread.interrupt();
        communicator.destroy();
    }

    private void unsetCloseCallback() {
        if (meta != null) {
            meta.ice_getConnection().setCloseCallback(closed -> {});
        }
    }
}
