package icejar;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.FileVisitOption;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.jar.JarFile;
import java.util.jar.JarEntry;
import java.util.logging.Handler;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import java.util.logging.Level;
import java.util.Properties;
import java.net.URLClassLoader;
import java.net.URL;
import java.nio.file.WatchService;
import java.nio.file.WatchKey;
import static java.nio.file.StandardWatchEventKinds.*;
import java.nio.file.FileSystems;
import java.nio.file.Path;

import com.moandjiezana.toml.Toml;


final class ClientManager {
    // Define accepted command line options
    private static final String SERVER_CONFIG_DIR_OPT = "-s";
    private static final String MODULE_DIR_OPT = "-m";
    private static final String VERBOSE_OPT = "-v";

    private static final String SERVER_CONFIG_EXTENSION = ".toml";
    private static final String MODULE_EXTENSION = ".jar";
    private static final String CLASS_EXTENSION = ".class";

    // server config field names
    private static final String ICE_ARGS_VAR = "ice_args";
    private static final String ICE_HOST_VAR = "ice_host";
    private static final String ICE_PORT_VAR = "ice_port";
    private static final String ICE_SECRET_VAR = "ice_secret";
    private static final String ENABLED_MODULES_VAR = "enabled_modules";
    private static final String SERVER_NAME_VAR = "server_name";
    private static final String SERVER_ID_VAR = "server_id";
    private static final String ENABLED_VAR = "enabled";

    // Directories from which ClientManager reads files
    private static File serverConfigDir = new File("servers");
    private static File moduleDir = new File("modules");

    private static Set<File> serverConfigFiles = new HashSet<File>();
    private static Set<File> moduleFiles = new HashSet<File>();
    private static Map<File, Class> moduleClasses = new HashMap<File, Class>();
    private static Map<File, Long> lastModifiedTimes = new HashMap<File, Long>();

    // Map config files to Client objects
    private static Map<File, Client> clientMap = new HashMap<File, Client>();

    private static LogManager logManager;
    private static Logger logger;


    private ClientManager() {}

    public static void main(String[] args) throws Exception {
        setupLogging();
        logger.info("Starting Icejar.");

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
        logger = Logger.getLogger("icejar");
        logManager.addLogger(logger);
        setLogFormatter();
    }

    private static void updateClientsAndModules() {
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
                    Toml defaults = new Toml().read(
                            ICE_ARGS_VAR + " = []\n"
                            + ICE_HOST_VAR + " = \"127.0.0.1\"\n"
                            + ICE_PORT_VAR + " = 6502\n"
                            + ENABLED_MODULES_VAR + " = []\n");
                    Toml overrides = new Toml().read(changedServerConfigFile);
                    Toml config = new Toml(defaults).read(overrides);

                    Boolean enabled = config.getBoolean(ENABLED_VAR);
                    // if `enabled` is defined AND false
                    if (enabled != null && !enabled) {
                        removeClient(changedServerConfigFile);
                        continue;
                    }

                    String[] iceArgs = config.getList(ICE_ARGS_VAR).toArray(new String[0]);
                    String iceHost = config.getString(ICE_HOST_VAR);
                    int icePort = config.getLong(ICE_PORT_VAR).intValue();

                    String iceSecret = config.getString(ICE_SECRET_VAR);

                    List<String> enabledModuleNames = config.getList(
                            ENABLED_MODULES_VAR, new ArrayList<String>());

                    Map<File, Class> enabledModuleClasses = new HashMap<File, Class>();
                    for (String moduleName: enabledModuleNames) {
                        File enabledModuleFile = moduleFileFromName(moduleName);
                        if (moduleClasses.containsKey(enabledModuleFile)) {
                            Class moduleClass = moduleClasses.get(enabledModuleFile);
                            enabledModuleClasses.put(enabledModuleFile, moduleClass);
                        } else {
                            // If the class is unavailable, still insert null
                            // so that if the class becomes available, the client
                            // can be updated.
                            enabledModuleClasses.put(enabledModuleFile, null);
                        }
                    }
                    Map<File, Module> enabledModules = moduleMapFromClassMap(
                            enabledModuleClasses, changedServerConfigFile);

                    String serverName = config.getString(SERVER_NAME_VAR);
                    Long serverID = config.getLong(SERVER_ID_VAR);

                    Client client;

                    if (clientMap.containsKey(changedServerConfigFile)) {
                        client = clientMap.get(changedServerConfigFile);
                    } else {
                        Logger clientLogger = Logger.getLogger(
                                "icejar.client."
                                + serverConfigFileName(changedServerConfigFile));
                        logManager.addLogger(clientLogger);

                        client = new Client(changedServerConfigFile, clientLogger);
                        clientMap.put(changedServerConfigFile, client);
                    }

                    // Clean up message passing queues & receivers for modules
                    // which are no longer enabled.
                    for (File previouslyEnabledModule: client.getEnabledModules()) {
                        if (!enabledModules.containsKey(previouslyEnabledModule)) {
                            String moduleName = moduleFileName(previouslyEnabledModule);
                            MessagePasser.removeModule(serverName, moduleName);
                        }
                    }

                    client.reconfigure(
                            iceArgs, iceHost, icePort, iceSecret,
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

    private static Toml readServerConfig(File serverConfigFile) throws Exception {
        if (serverConfigFile.isDirectory()) {
            Toml config = new Toml();

            for (File subFile: serverConfigFile.listFiles()) {
                Toml subConfig = readServerConfig(subFile);

                if (subConfig != null) {
                    config.read(subConfig);
                }
            }

            return config;
        } else if (
                serverConfigFile.isFile()
                && serverConfigFile.getName().endsWith(SERVER_CONFIG_EXTENSION))
        {
            return new Toml().read(serverConfigFile);
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

            Map<File, Module> modulesToReload = new HashMap<File, Module>();
            for (File changedModuleFile: changedModuleFiles) {
                if (client.hasModuleFile(changedModuleFile)) {
                    // Clean up message passing queues & receivers from
                    // previous instance
                    String moduleName = moduleFileName(changedModuleFile);
                    MessagePasser.removeModule(serverName, moduleName);

                    Class moduleClass = moduleClasses.get(changedModuleFile);
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

    private static void removeClient(File configFile) {
        MessagePasser.removeServer(serverConfigFileName(configFile));

        if (clientMap.containsKey(configFile)) {
            Client client = clientMap.get(configFile);
            client.cleanup();
            clientMap.remove(configFile);
        }
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

        Module module = null;
        try {
            Object moduleObj = moduleClass.getDeclaredConstructor().newInstance();
            module = Module.class.cast(moduleObj);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Intantiating `Module` class for " + moduleClass + " threw:", e);
            return null;
        }

        String serverName = serverConfigFileName(serverConfigFile);
        String moduleName = moduleFileName(moduleFile);

        Logger moduleLogger = Logger.getLogger(
                "icejar.client." + serverName + ".module." + moduleName);
        logManager.addLogger(moduleLogger);
        module.setLogger(moduleLogger);

        MessagePasser.Coordinator coordinator =
            MessagePasser.createCoordinator(serverName, moduleName);

        module.setupMessagePassing(coordinator);

        return module;
    }

    private static Map<File, Module> moduleMapFromClassMap(
            Map<File, Class> classMap, File serverConfigFile)
    {
        Map<File, Module> moduleMap = new HashMap<File, Module>();

        for (Map.Entry<File, Class> classEntry: classMap.entrySet()) {
            File moduleFile = classEntry.getKey();
            Class<?> moduleClass = classEntry.getValue();

            moduleMap.put(
                    moduleFile,
                    instanceModuleClass(moduleClass, moduleFile, serverConfigFile));
        }

        return moduleMap;
    }


    private static void parseArgs(String[] args) {
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {

                case SERVER_CONFIG_DIR_OPT:
                    serverConfigDir = new File(args[i + 1]);
                    i++;
                    break;

                case MODULE_DIR_OPT:
                    moduleDir = new File(args[i + 1]);
                    i++;
                    break;

                case VERBOSE_OPT:
                    Properties p = new Properties();
                    for (Handler handler: logManager.getLogger("").getHandlers()) {
                        p.setProperty(handler.getClass().getName() + ".level", "FINEST");
                    }
                    p.setProperty(".level", "FINEST");
                    p.setProperty("handlers", logManager.getProperty("handlers"));

                    try {
                        ByteArrayOutputStream s = new ByteArrayOutputStream();
                        p.store(s, "");
                        logManager.readConfiguration(
                                new ByteArrayInputStream(s.toByteArray()));
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }

                    setLogFormatter();
                    break;
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

        try {
            Files.walk(dir.toPath(), FileVisitOption.FOLLOW_LINKS).forEach(path -> {
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
        Set<File> union = new HashSet<File>();
        union.addAll(newFiles);
        union.addAll(oldFiles);

        // Files which are absent from at least one set
        Set<File> symmetricDifference = new HashSet<File>();
        for (File file: union) {
            if (!newFiles.contains(file) || !oldFiles.contains(file)) {
                symmetricDifference.add(file);
            }
        }

        // Files which are present in both sets
        Set<File> intersection = new HashSet<File>();
        for (File file: union) {
            if (newFiles.contains(file) && oldFiles.contains(file)) {
                intersection.add(file);
            }
        }

        // Files are modified if their modified time has changed
        Set<File> modified = new HashSet<File>();
        for (File file: intersection) {
            if (file.lastModified() != lastModifiedTimes.get(file)) {
                modified.add(file);
            }
        }

        Set<File> changedFiles = new HashSet<File>();
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
                            Class moduleClass = Class.forName(
                                    className, true, classLoader);

                            if (Module.class.isAssignableFrom(moduleClass)) {
                                moduleClasses.put(changedModuleFile, moduleClass);
                                break;
                            }
                        } catch (NoClassDefFoundError e) {
                        } catch (ClassNotFoundException e) {}
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
        for (Client client: clientMap.values()) {
            client.cleanup();
        }
    }
}
