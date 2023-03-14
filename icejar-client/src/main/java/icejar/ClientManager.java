package icejar;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.FileVisitOption;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.FileInputStream;
import java.io.SequenceInputStream;
import java.util.*;
import java.util.stream.Stream;
import java.util.jar.JarFile;
import java.util.jar.JarEntry;
import java.util.logging.Handler;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import java.util.logging.Level;
import java.net.URLClassLoader;
import java.net.URL;
import java.nio.file.WatchService;
import java.nio.file.WatchKey;
import java.nio.file.Path;
import static java.nio.file.StandardWatchEventKinds.*;
import java.nio.file.FileSystems;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import com.moandjiezana.toml.Toml;


/*
 * This is the main class that wrangles all the """resources""" used by the
 * program.
 *
 * Resources need to be initialized when:
 * - A client is re-configured (server configuration file changes)
 * - Module jar files change
 * 
 * Resources need to be cleaned up when:
 * - A client gets removed/disabled
 * - A previously enabled module is no longer enabled for a client
 * - The program gets shut down
 */

final class ClientManager {
    // Define accepted command line options
    private static final String SERVER_CONFIG_DIR_OPT = "-s";
    private static final String MODULE_DIR_OPT = "-m";
    private static final String DB_DIR_OPT = "-d";
    private static final String VERBOSE_OPT = "-v";

    private static final String SERVER_CONFIG_EXTENSION = ".toml";
    private static final String MODULE_EXTENSION = ".jar";
    private static final String CLASS_EXTENSION = ".class";

    // server config field names
    private static final String SERVER_TABLE_NAME = "server";
    private static final String ICE_ARGS_VAR = "ice_args";
    private static final String ICE_HOST_VAR = "ice_host";
    private static final String ICE_PORT_VAR = "ice_port";
    private static final String ICE_SECRET_VAR = "ice_secret";
    private static final String CALLBACK_HOST_VAR = "callback_host";
    private static final String CALLBACK_PORT_VAR = "callback_port";
    private static final String ENABLED_MODULES_VAR = "enabled_modules";
    private static final String SERVER_NAME_VAR = "server_name";
    private static final String SERVER_ID_VAR = "server_id";
    private static final String ENABLED_VAR = "enabled";

    // Directories from which ClientManager reads files
    private static File serverConfigDir = new File("servers");
    private static File moduleDir = new File("modules");
    private static File dbDir = new File("data");

    // Logger names
    private static final String BASE_LOGGER = "icejar";
    private static final String CLIENT_LOGGER = "client";
    private static final String MODULE_LOGGER = "module";

    private static Set<File> serverConfigFiles = new HashSet<>();
    private static Set<File> moduleFiles = new HashSet<>();
    private static final Map<File, Class<?>> moduleClasses = new HashMap<>();
    private static final Map<File, Long> lastModifiedTimes = new HashMap<>();

    // Map config files to Client objects
    private static final Map<File, Client> clientMap = new HashMap<>();

    // Map db files to db Connections. These are kept track of so we can make
    // sure there is only ever 1 open connection to each database file.
    private static final Map<File, Connection> connectionMap = new HashMap<>();

    private static LogManager logManager;
    private static Logger logger;


    private ClientManager() {}

    public static void main(String[] args) throws Exception {
        setupLogging();

        parseArgs(args);
        Runtime.getRuntime().addShutdownHook(new Thread(ClientManager::cleanup));

        updateClientsAndModules();

        try {
            WatchService watcher = FileSystems.getDefault().newWatchService();
            serverConfigDir.toPath().register(
                    watcher, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE);
            moduleDir.toPath().register(
                    watcher, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE);

            WatchKey key;
            while ((key = watcher.take()) != null) {
                key.pollEvents();
                updateClientsAndModules();
                key.reset();
            }
        } catch (Exception e) {
            logger.warning(String.format("""
                        Watcher service threw: `%s`. Falling back to checking \
                        for module/configuration changes every 5 seconds.
                        """, e));

            while (true) {
                Thread.sleep(5000);
                updateClientsAndModules();
            }
        }
    }


    private static void setLogFormatter() {
        LogFormatter f = new LogFormatter();
        for (Handler handler: logManager.getLogger("").getHandlers()) {
            handler.setFormatter(f);
        }
    }

    private static void setupLogging() {
        logManager = LogManager.getLogManager();
        logger = Logger.getLogger(BASE_LOGGER);
        logManager.addLogger(logger);
        setLogFormatter();
    }

    private static void updateClientsAndModules() {
        System.gc();
        // Update modules (if they changed)
        updateModules();
        // Update clients (if they changed)
        updateClients();
    }

    private static void updateClients() {
        Set<File> serverConfigFiles = getServerConfigFiles();
        Set<File> changedServerConfigFiles = getChangedServerConfigFiles(
                serverConfigFiles);
        ClientManager.serverConfigFiles = serverConfigFiles;
        updateLastModifiedTimes(changedServerConfigFiles);

        for (File changedServerConfigFile: changedServerConfigFiles) {
            if (changedServerConfigFile.exists()) {
                try {
                    // Read configuration
                    Toml config = new Toml();

                    try (InputStream overrides = readServerConfig(changedServerConfigFile)) {
                        config.read(overrides);
                    }

                    Toml serverConfig = config.getTable(SERVER_TABLE_NAME);

                    Boolean enabled = serverConfig.getBoolean(ENABLED_VAR);
                    // if `enabled` is defined AND false
                    if (enabled != null && !enabled) {
                        removeClient(changedServerConfigFile);
                        continue;
                    }

                    @SuppressWarnings("SuspiciousToArrayCall")
                    String[] iceArgs = Optional.ofNullable(
                            serverConfig.getList(ICE_ARGS_VAR))
                        .orElse(new ArrayList<>())
                        .toArray(new String[0]);

                    String iceHost = Optional.ofNullable(
                            serverConfig.getString(ICE_HOST_VAR))
                        .orElse("127.0.0.1");

                    int icePort = Optional.ofNullable(
                            serverConfig.getLong(ICE_PORT_VAR))
                        .orElse(6502L)
                        .intValue();

                    String callbackHost = Optional.ofNullable(
                            serverConfig.getString(CALLBACK_HOST_VAR))
                        .orElse("127.0.0.1");

                    int callbackPort = Optional.ofNullable(
                            serverConfig.getLong(CALLBACK_PORT_VAR))
                        .orElse(-1L)
                        .intValue();

                    List<String> enabledModuleNames = Optional.ofNullable(
                            serverConfig.getList(ENABLED_MODULES_VAR, new ArrayList<String>()))
                        .orElse(new ArrayList<>());

                    String iceSecret = serverConfig.getString(ICE_SECRET_VAR);

                    String serverName = serverConfig.getString(SERVER_NAME_VAR);
                    Long serverID = serverConfig.getLong(SERVER_ID_VAR);

                    // Get Client
                    Client client;

                    if (clientMap.containsKey(changedServerConfigFile)) {
                        client = clientMap.get(changedServerConfigFile);
                    } else {
                        Logger clientLogger = Logger.getLogger(
                                String.join(".",
                                    BASE_LOGGER,
                                    CLIENT_LOGGER,
                                    serverConfigFileName(changedServerConfigFile)));
                        logManager.addLogger(clientLogger);

                        client = new Client(clientLogger);
                        clientMap.put(changedServerConfigFile, client);
                    }

                    Map<File, Class<?>> enabledModuleClasses = new HashMap<>();
                    for (String moduleName: enabledModuleNames) {
                        File enabledModuleFile = moduleFileFromName(moduleName);
                        if (moduleClasses.containsKey(enabledModuleFile)) {
                            Class<?> moduleClass = moduleClasses.get(enabledModuleFile);
                            enabledModuleClasses.put(enabledModuleFile, moduleClass);
                        } else {
                            // If the class is unavailable, still insert null
                            // so that if the class becomes available, the client
                            // can be updated.
                            enabledModuleClasses.put(enabledModuleFile, null);
                        }
                    }
                    Map<File, Module> enabledModules = moduleMapFromClassMap(
                            enabledModuleClasses, changedServerConfigFile, client);

                    // Clean up message passing and database connections for
                    // modules which are no longer enabled.
                    for (File previouslyEnabledModule: client.getEnabledModules()) {
                        if (!enabledModules.containsKey(previouslyEnabledModule)) {
                            removeModule(
                                    previouslyEnabledModule,
                                    changedServerConfigFile);
                        }
                    }


                    client.reconfigure(
                            iceArgs, iceHost, icePort, iceSecret,
                            callbackHost, callbackPort,
                            enabledModules, serverName, serverID, config);
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Parsing `" + changedServerConfigFile + "` threw: " + e.getMessage());
                }
            } else {
                // If the file was removed, clean up the client and remove it.
                removeClient(changedServerConfigFile);
            }
        }
    }

    private static InputStream readServerConfig(File serverConfigFile) throws Exception {
        if (serverConfigFile.isDirectory()) {
            ArrayList<InputStream> streams = new ArrayList<>();

            for (File subFile: Objects.requireNonNull(serverConfigFile.listFiles())) {
                InputStream s = readServerConfig(subFile);

                if (s != null) {
                    streams.add(s);
                }
            }

            return new SequenceInputStream(Collections.enumeration(streams));
        } else if (
                serverConfigFile.isFile()
                && serverConfigFile.getName().endsWith(SERVER_CONFIG_EXTENSION))
        {
            return new FileInputStream(serverConfigFile);
        } else {
            return null;
        }
    }

    private static void updateModules() {
        Set<File> moduleFiles = getModuleFiles();

        Set<File> changedModuleFiles = getChangedModuleFiles(moduleFiles);

        ClientManager.moduleFiles = moduleFiles;

        updateLastModifiedTimes(changedModuleFiles);
        updateModuleClasses(changedModuleFiles);

        // Tell existing clients about the modules which must be reloaded
        for (Map.Entry<File, Client> clientEntry: clientMap.entrySet()) {
            File serverConfigFile = clientEntry.getKey();
            Client client = clientEntry.getValue();

            String serverName = serverConfigFileName(serverConfigFile);

            Map<File, Module> modulesToReload = new HashMap<>();
            for (File changedModuleFile: changedModuleFiles) {
                if (client.hasModuleFile(changedModuleFile)) {
                    removeModule(changedModuleFile, serverConfigFile);

                    Class<?> moduleClass = moduleClasses.get(changedModuleFile);
                    Module module = instanceModuleClass(
                            moduleClass, changedModuleFile, serverConfigFile);
                    modulesToReload.put(changedModuleFile, module);
                }
            }

            if (!modulesToReload.isEmpty()) {
                client.reloadModules(modulesToReload);
            }
        }
    }

    // Remove a client and drop all resources related to that client
    private static void removeClient(File configFile) {
        MessagePasser.removeServer(serverConfigFileName(configFile));

        if (clientMap.containsKey(configFile)) {
            Client client = clientMap.get(configFile);
            client.cleanup();
            clientMap.remove(configFile);

            for (File moduleFile: client.getEnabledModules()) {
                closeDatabaseConnection(configFile, moduleFile);
            }
        }
    }

    // Remove/close resources associated with a module instance for a particular
    // server, i.e. for a specific instance of a loaded module class
    private static void removeModule(File moduleFile, File serverConfigFile) {
        String moduleName = moduleFileName(moduleFile);
        String serverName = serverConfigFileName(serverConfigFile);

        MessagePasser.removeModule(serverName, moduleName);

        closeDatabaseConnection(serverConfigFile, moduleFile);
    }

    private static void closeDatabaseConnection(
            File serverConfigFile, File moduleFile)
    {
        File dbFile = getDatabaseFile(serverConfigFile, moduleFile);

        Connection c = connectionMap.get(dbFile);

        if (c != null) {
            try {
                c.commit();
                c.close();
            } catch (SQLException ignored) {}
        }

        connectionMap.remove(dbFile);
    }

    private static Connection openDatabaseConnection(
            File serverConfigFile, File moduleFile) throws SQLException
    {
        closeDatabaseConnection(serverConfigFile, moduleFile);

        File dbFile = getDatabaseFile(serverConfigFile, moduleFile);
        new File(dbFile.getParent()).mkdirs();

        String connString = "jdbc:sqlite:" + dbFile;
        Connection c = DriverManager.getConnection(connString);

        connectionMap.put(dbFile, c);

        return c;
    }

    private static String stripPrefixAndSuffix(
            String s, String prefix, String suffix)
    {
        if (s.startsWith(prefix)) {
            s = s.substring(prefix.length());
        }

        if (s.endsWith(suffix)) {
            s = s.substring(0, s.length() - suffix.length());
        }

        return s;
    }

    private static String serverConfigFileName(File serverConfigFile) {
        return stripPrefixAndSuffix(
                serverConfigFile.toString(),
                serverConfigDir.toString() + "/",
                SERVER_CONFIG_EXTENSION);
    }

    private static String moduleFileName(File moduleFile) {
        return stripPrefixAndSuffix(
                moduleFile.toString(),
                moduleDir.toString() + "/",
                MODULE_EXTENSION);
    }

    private static Module instanceModuleClass(
            Class<?> moduleClass, File moduleFile, File serverConfigFile)
    {
        if (moduleClass == null) {
            return null;
        }

        Module module;
        try {
            Object moduleObj = moduleClass.getDeclaredConstructor().newInstance();
            module = (Module) moduleObj;
        } catch (Exception e) {
            logger.log(Level.WARNING, "Instantiating `Module` class for " + moduleClass + " threw:", e);
            return null;
        }

        String serverName = serverConfigFileName(serverConfigFile);
        String moduleName = moduleFileName(moduleFile);

        Logger moduleLogger = Logger.getLogger(
                String.join(".",
                    BASE_LOGGER,
                    CLIENT_LOGGER,
                    serverName,
                    MODULE_LOGGER,
                    moduleName));
        logManager.addLogger(moduleLogger);
        module.setLogger(moduleLogger);

        MessagePasser.Coordinator coordinator =
            MessagePasser.createCoordinator(serverName, moduleName);

        module.setupMessagePassing(coordinator);

        try {
            Connection c = openDatabaseConnection(serverConfigFile, moduleFile);
            module.setDatabaseConnection(c);
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Opening database connection for " + moduleClass + " failed.");
        }

        return module;
    }

    private static Map<File, Module> moduleMapFromClassMap(
            Map<File, Class<?>> classMap, File serverConfigFile, Client client)
    {
        Map<File, Module> moduleMap = new HashMap<>();

        for (Map.Entry<File, Class<?>> classEntry: classMap.entrySet()) {
            File moduleFile = classEntry.getKey();
            Class<?> moduleClass = classEntry.getValue();

            // Re-use module instance, if possible
            Module module = client.getEnabledModule(moduleFile);
            if (module == null) {
                module = instanceModuleClass(
                        moduleClass, moduleFile, serverConfigFile);
            }

            moduleMap.put(moduleFile, module);
        }

        return moduleMap;
    }


    private static void parseArgs(String[] args) {
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case SERVER_CONFIG_DIR_OPT -> {
                    serverConfigDir = new File(args[i + 1]);
                    i++;
                }
                case MODULE_DIR_OPT -> {
                    moduleDir = new File(args[i + 1]);
                    i++;
                }
                case DB_DIR_OPT -> {
                    dbDir = new File(args[i + 1]);
                    i++;
                }
                case VERBOSE_OPT -> {
                    // Set the log level for loggers descending from the base
                    // logger (icejar.*) to FINEST

                    Properties p = new Properties();
                    for (Handler handler : logManager.getLogger("").getHandlers()) {
                        p.setProperty(handler.getClass().getName() + ".level", "FINEST");
                    }
                    p.setProperty(BASE_LOGGER + ".level", "FINEST");
                    p.setProperty("handlers", logManager.getProperty("handlers"));

                    // Write the properties to a byte array and read them into
                    // the LogManager
                    try {
                        ByteArrayOutputStream s = new ByteArrayOutputStream();
                        p.store(s, "");
                        logManager.readConfiguration(
                                new ByteArrayInputStream(s.toByteArray()));
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    setLogFormatter();
                }
            }
        }
    }


    private static Set<File> getServerConfigFiles() {
        Set<File> serverConfigFiles = new HashSet<>();

        File[] contents = serverConfigDir.listFiles();
        if (contents != null) {
            for (File serverConfigFile: contents) {
                if (
                        serverConfigFile.isDirectory()
                        || serverConfigFile.getName().endsWith(SERVER_CONFIG_EXTENSION))
                {
                    serverConfigFiles.add(serverConfigFile);
                }
            }
        }

        return serverConfigFiles;
    }

    private static Set<File> getModuleFiles() {
        return getFilesWithExtensionFromDir(moduleDir, MODULE_EXTENSION);
    }

    // Returns the files in the given directory which have names ending in the
    // given extension.
    private static Set<File> getFilesWithExtensionFromDir(File dir, String ext) {
        Set<File> files = new HashSet<>();

        try (Stream<Path> paths = Files.walk(dir.toPath(), FileVisitOption.FOLLOW_LINKS)) {
            paths.forEach(path -> {
                String fileName = path.getFileName().toString();
                if (fileName.endsWith(MODULE_EXTENSION)) {
                    files.add(path.toFile());
                }
            });
        } catch (IOException e) {
            logger.warning("Walking directory contents of `" + dir + "` threw: " + e);
        }

        return files;
    }

    private static File getDatabaseFile(
            File serverConfigFile, File moduleFile)
    {
        return new File(
                dbDir + "/" + serverConfigFileName(serverConfigFile) + "/"
                + moduleFileName(moduleFile) + "/" + "db.sqlite");
    }


    private static Set<File> getChangedServerConfigFiles(
            Set<File> newServerConfigFiles)
    {
        return getChangedFiles(newServerConfigFiles, serverConfigFiles);
    }

    private static Set<File> getChangedModuleFiles(Set<File> newModuleFiles) {
        return getChangedFiles(newModuleFiles, moduleFiles);
    }

    // Return the set of files that were changed between oldFiles and newFiles.
    // A change is defined as a file being created, modified, or removed.
    private static Set<File> getChangedFiles(
            Set<File> newFiles, Set<File> oldFiles)
    {
        Set<File> union = new HashSet<>();
        union.addAll(newFiles);
        union.addAll(oldFiles);

        // Files which are absent from at least one set
        Set<File> symmetricDifference = new HashSet<>();
        for (File file: union) {
            if (!newFiles.contains(file) || !oldFiles.contains(file)) {
                symmetricDifference.add(file);
            }
        }

        // Files which are present in both sets
        Set<File> intersection = new HashSet<>();
        for (File file: union) {
            if (newFiles.contains(file) && oldFiles.contains(file)) {
                intersection.add(file);
            }
        }

        // Files are modified if their modified time has changed
        Set<File> modified = new HashSet<>();
        for (File file: intersection) {
            if (file.lastModified() != lastModifiedTimes.get(file)) {
                modified.add(file);
            }
        }

        Set<File> changedFiles = new HashSet<>();
        changedFiles.addAll(symmetricDifference);
        changedFiles.addAll(modified);
        return changedFiles;
    }


    private static File moduleFileFromName(String moduleName) {
        return new File(moduleDir, moduleName + MODULE_EXTENSION);
    }


    private static void updateLastModifiedTimes(Set<File> files) {
        for (File file: files) {
            if (file.exists()) {
                lastModifiedTimes.put(file, file.lastModified());
            } else {
                lastModifiedTimes.remove(file);
            }
        }
    }

    private static void updateModuleClasses(Set<File> changedModuleFiles) {
        for (File changedModuleFile: changedModuleFiles) {
            // Remove classes for files which no longer exist
            if (!changedModuleFile.exists()) {
                moduleClasses.remove(changedModuleFile);
                continue;
            }

            try (JarFile jar = new JarFile(changedModuleFile)) {

                URL moduleFileURL = changedModuleFile.toURI().toURL();
                URL[] urls = { moduleFileURL };

                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    URLClassLoader classLoader = new URLClassLoader(urls);

                    JarEntry entry = entries.nextElement();
                    String className = entry.getName().replace('/', '.');
                    if (className.endsWith(CLASS_EXTENSION)) {
                        className = className.substring(
                                0, className.length() - CLASS_EXTENSION.length());

                        // The classes loaded from module JAR files might use
                        // classes that aren't available to the current
                        // ClassLoader. If this is the case, we just skip
                        // trying to load the class.
                        try {
                            Class<?> moduleClass = Class.forName(
                                    className, true, classLoader);

                            if (Module.class.isAssignableFrom(moduleClass)) {
                                moduleClasses.put(changedModuleFile, moduleClass);
                                break;
                            }
                        } catch (NoClassDefFoundError | ClassNotFoundException ignored) {}
                    }
                }
            } catch (IOException e) {
                logger.log(Level.WARNING, "Reading JAR file `" + changedModuleFile + "` threw: " + e);
            }
        }
    }

    private static void cleanup() {
        logger.info("Cleaning up active clients and shutting down...");
        cleanupClients();
    }

    private static void cleanupClients() {
        Set<File> files = new HashSet<>(clientMap.keySet());
        for (File file: files) {
            removeClient(file);
        }
    }
}
