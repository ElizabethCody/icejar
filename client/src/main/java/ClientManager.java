package icejar;

import java.io.File;
import java.io.FilenameFilter;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;
import java.util.Optional;
import java.net.URLClassLoader;
import java.net.URL;
import java.nio.file.WatchService;
import java.nio.file.WatchKey;
import static java.nio.file.StandardWatchEventKinds.*;
import java.nio.file.FileSystems;
import java.nio.file.Path;

import com.moandjiezana.toml.Toml;


public final class ClientManager {
    // Define accepted command line options
    private static final String SERVER_CONFIG_DIR_OPT = "-s";
    private static final String MODULE_DIR_OPT = "-m";

    protected static final String SERVER_CONFIG_EXTENSION = ".toml";
    protected static final String MODULE_EXTENSION = ".jar";

    // server config field names
    private static final String ICE_ARGS_VAR = "ice_args";
    private static final String ICE_PROXY_STRING_VAR = "ice_proxy_string";
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


    public static void main(String[] args) {
        parseArgs(args);
        Runtime.getRuntime().addShutdownHook(new Thread(ClientManager::cleanupClients));

        updateClientsAndModules();

        try {
            WatchService watcher = FileSystems.getDefault().newWatchService();
            serverConfigDir.toPath().register(watcher, ENTRY_MODIFY, ENTRY_DELETE);
            moduleDir.toPath().register(watcher, ENTRY_MODIFY, ENTRY_DELETE);

            WatchKey key;
            while ((key = watcher.take()) != null) {
                key.pollEvents();
                updateClientsAndModules();
                key.reset();
            }
        } catch (Exception e) {
            StringBuilder errorMsg = new StringBuilder()
                .append("Falling back to polling after Watcher service threw: `")
                .append(e)
                .append("`");
            System.err.println(errorMsg);
            while (true) {
                try {
                    Thread.sleep(5000);
                } catch (Exception e2) {
                    System.err.println(e2);
                }
                updateClientsAndModules();
            }
        }
    }


    private static void updateClientsAndModules() {
        Set<File> serverConfigFiles = getServerConfigFiles();
        Set<File> moduleFiles = getModuleFiles();

        Set<File> changedServerConfigFiles = getChangedServerConfigFiles(serverConfigFiles);
        Set<File> changedModuleFiles = getChangedModuleFiles(moduleFiles);

        ClientManager.moduleFiles = moduleFiles;
        ClientManager.serverConfigFiles = serverConfigFiles;

        updateLastModifiedTimes(changedServerConfigFiles);
        updateLastModifiedTimes(changedModuleFiles);
        updateModuleClasses(changedModuleFiles);

        // Clients are reloaded if their configuration files were changed.
        for (File changedServerConfigFile: changedServerConfigFiles) {
            // Clean up existing instance
            if (clientMap.containsKey(changedServerConfigFile)) {
                Client currentClient = clientMap.get(changedServerConfigFile);
                currentClient.cleanup();
                clientMap.remove(changedServerConfigFile);
            }

            if (changedServerConfigFile.exists()) {
                try {
                    Toml config = new Toml().read(changedServerConfigFile);
                    
                    Boolean enabled = config.getBoolean(ENABLED_VAR);
                    // if `enabled` is defined AND false
                    if (enabled != null && !enabled) {
                        continue;
                    }

                    String[] iceArgs = config.getList(ICE_ARGS_VAR).toArray(new String[0]);
                    String iceProxyString = config.getString(ICE_PROXY_STRING_VAR);

                    Optional<String> iceSecret = Optional.ofNullable(config.getString(ICE_SECRET_VAR));

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
                    Map<File, Module> enabledModules = moduleMapFromClassMap(enabledModuleClasses);

                    Optional<String> serverName = Optional.ofNullable(config.getString(SERVER_NAME_VAR));
                    Optional<Long> serverID = Optional.ofNullable(config.getLong(SERVER_ID_VAR));

                    Client newClient = new Client(
                            changedServerConfigFile, iceArgs, iceProxyString,
                            iceSecret, enabledModules, serverName, serverID,
                            config);

                    clientMap.put(changedServerConfigFile, newClient);
                } catch (Exception e) {
                    ErrorHelper.printException("loading server config file", changedServerConfigFile, e);
                }
            }
        }

        // Clients whose configuration files were not changed still need to be
        // notified of any changes to modules in case they need to reload them.
        for (Map.Entry<File, Client> clientEntry: clientMap.entrySet()) {
            if (!changedServerConfigFiles.contains(clientEntry.getKey())) {
                Client client = clientEntry.getValue();

                Map<File, Module> modulesToReload = new HashMap<File, Module>();
                for (File changedModuleFile: changedModuleFiles) {
                    if (client.hasModuleFile(changedModuleFile)) {
                        Class moduleClass = moduleClasses.get(changedModuleFile);
                        Module module = instanceModuleClass(moduleClass);
                        modulesToReload.put(changedModuleFile, module);
                    }
                }

                if (!modulesToReload.isEmpty()) {
                    client.reloadModules(modulesToReload);
                }
            }
        }
    }

    private static Module instanceModuleClass(Class<?> moduleClass) {
        if (moduleClass == null) {
            return null;
        }

        try {
            Object moduleObj = moduleClass.getDeclaredConstructor().newInstance();
            Module module = Module.class.cast(moduleObj);
            return module;
        } catch (Exception e) {
            ErrorHelper.printException("instancing `Module` class for", moduleClass, e);
            return null;
        }
    }

    private static Map<File, Module> moduleMapFromClassMap(Map<File, Class> classMap) {
        Map<File, Module> moduleMap = new HashMap<File, Module>();

        for (Map.Entry<File, Class> classEntry: classMap.entrySet()) {
            File moduleFile = classEntry.getKey();
            Class<?> moduleClass = classEntry.getValue();

            moduleMap.put(moduleFile, instanceModuleClass(moduleClass));
        }

        return moduleMap;
    }


    private static void parseArgs(String[] args) {
        for (int i = 0; i < args.length; i += 2) {
            switch (args[i]) {

                case SERVER_CONFIG_DIR_OPT:
                    serverConfigDir = new File(args[i + 1]);
                    break;

                case MODULE_DIR_OPT:
                    moduleDir = new File(args[i + 1]);
                    break;
            }
        }
    }


    private static Set<File> getServerConfigFiles() {
        return getFilesWithExtensionFromDir(serverConfigDir, SERVER_CONFIG_EXTENSION);
    }

    private static Set<File> getModuleFiles() {
        return getFilesWithExtensionFromDir(moduleDir, MODULE_EXTENSION);
    }

    private static Set<File> getFilesWithExtensionFromDir(File dir, String ext) {
        Set<File> files = new HashSet<File>();

        FilenameFilter filter = (d, fileName) -> fileName.endsWith(ext);

        File[] filteredFiles = dir.listFiles(filter);

        if (filteredFiles != null) {
            for (File file: filteredFiles) {
                files.add(file);
            }
        }

        return files;
    }


    private static Set<File> getChangedServerConfigFiles(Set<File> newServerConfigFiles) {
        return getChangedFiles(newServerConfigFiles, serverConfigFiles);
    }

    private static Set<File> getChangedModuleFiles(Set<File> newModuleFiles) {
        return getChangedFiles(newModuleFiles, moduleFiles);
    }

    // Return the set of files that were changed between oldFiles and newFiles.
    // A change is defined as a file being created, modified, or removed.
    private static Set<File> getChangedFiles(Set<File> newFiles, Set<File> oldFiles) {
        Set<File> union = new HashSet<File>();
        union.addAll(newFiles);
        union.addAll(oldFiles);

        Set<File> symmetricDifference = new HashSet<File>();
        for (File file: union) {
            if (!newFiles.contains(file) || !oldFiles.contains(file)) {
                symmetricDifference.add(file);
            }
        }

        Set<File> intersection = new HashSet<File>();
        for (File file: union) {
            if (newFiles.contains(file) && oldFiles.contains(file)) {
                intersection.add(file);
            }
        }

        Set<File> modified = new HashSet<File>();
        for (File file: intersection) {
            if (file.lastModified() > lastModifiedTimes.get(file)) {
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
            lastModifiedTimes.remove(file);
            if (file.exists()) {
                lastModifiedTimes.put(file, file.lastModified());
            }
        }
    }

    private static synchronized void updateModuleClasses(Set<File> changedModuleFiles) {
        for (File changedModuleFile: changedModuleFiles) {
            if (changedModuleFile.exists()) {
                try {
                    URL moduleFileURL = changedModuleFile.toURI().toURL();
                    URL[] urls = { moduleFileURL };
                    URLClassLoader classLoader = new URLClassLoader(urls);
                    Class moduleClass = Class.forName("Module", true, classLoader);

                    moduleClasses.put(changedModuleFile, moduleClass);
                } catch (Exception e) {
                    ErrorHelper.printException("loading `Module` class from", changedModuleFile, e);
                    moduleClasses.remove(changedModuleFile);
                }
            } else {
                moduleClasses.remove(changedModuleFile);
            }
        }
    }

    private static void cleanupClients() {
        for (Client client: clientMap.values()) {
            client.cleanup();
        }
    }
}
