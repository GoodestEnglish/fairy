package org.fairy.module;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.NonNull;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fairy.Fairy;
import org.fairy.FairyPlatform;
import org.fairy.bean.BeanContext;
import org.fairy.bean.Beans;
import org.fairy.bean.PreInitialize;
import org.fairy.bean.Service;
import org.fairy.plugin.Plugin;
import org.fairy.plugin.PluginListenerAdapter;
import org.fairy.plugin.PluginManager;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

@Service(name = "module")
public class ModuleService {

    public static final int PLUGIN_LISTENER_PRIORITY = BeanContext.PLUGIN_LISTENER_PRIORITY - 1;

    private static final Logger LOGGER = LogManager.getLogger();
    private static Map<String, Integer> PENDING_MODULES = new HashMap<>();
    public static void init() {
        PluginManager.INSTANCE.registerListener(new PluginListenerAdapter() {
            @Override
            public void onPluginInitial(Plugin plugin) {
                if (PENDING_MODULES != null) {
                    for (String module : plugin.getDescription().getModules()) {
                        PENDING_MODULES.compute(module, (m, v) -> {
                            if (v == null) {
                                return 1;
                            }
                            return v + 1;
                        });
                    }
                } else {
                    final ModuleService moduleService = Beans.get(ModuleService.class);
                    for (String name : plugin.getDescription().getModules()) {
                        final Module module = moduleService.registerByName(name);

                        if (module == null) {
                            plugin.closeAndReportException();
                            return;
                        }

                        module.addRef(); // add reference
                    }
                }
            }

            @Override
            public void onPluginDisable(Plugin plugin) {
                for (String name : plugin.getDescription().getModules()) {
                    if (PENDING_MODULES != null) {
                        final int refCount = PENDING_MODULES.compute(name, (m, v) -> {
                            if (v == null) {
                                return 0;
                            }
                            return v - 1;
                        });

                        if (refCount <= 0) {
                            PENDING_MODULES.remove(name);
                        }
                    } else {
                        final ModuleService moduleService = Beans.get(ModuleService.class);
                        final Module module = moduleService.getByName(name);

                        if (module == null) {
                            continue;
                        }

                        if (module.removeRef() <= 0) {
                            moduleService.unregister(module);
                        }
                    }
                }
            }

            @Override
            public int priority() {
                return PLUGIN_LISTENER_PRIORITY;
            }
        });
    }

    private final Map<String, Module> moduleByName = new ConcurrentHashMap<>();

    @PreInitialize
    public void onPreInitialize() {
        for (Map.Entry<String, Integer> entry : PENDING_MODULES.entrySet()) {
            // Don't register if nothing referenced
            if (entry.getValue() <= 0) {
                continue;
            }
            this.registerByName(entry.getKey());
        }
        PENDING_MODULES = null;
    }

    @Nullable
    public Module getByName(String name) {
        return moduleByName.get(name);
    }

    @Nullable
    public Module registerByName(String name) {
        try {
            final Path path = ModuleDownloader.download(new File(FairyPlatform.INSTANCE.getDataFolder(), "modules/" + name + ".jar").toPath(), name);

            return this.registerByPath(path);
        } catch (IOException e) {
            LOGGER.error("Unexpected IO error", e);
        } catch (IllegalArgumentException e) {
            LOGGER.error(e);
        }

        return null;
    }

    @Nullable
    public Module registerByPath(Path path) {
        return this.registerByPath(path, false);
    }

    @Nullable
    public Module registerByPath(Path path, boolean canAbstract) {
        Fairy.getPlatform().getClassloader().addJarToClasspath(path);

        Module module = null;
        try {
            final JarFile jarFile = new JarFile(path.toFile());
            final ZipEntry zipEntry = jarFile.getEntry("module.json");
            if (zipEntry == null) {
                LOGGER.error("Unable to find module.json from " + path);
                return null;
            }

            final JsonObject jsonObject = new Gson().fromJson(new InputStreamReader(jarFile.getInputStream(zipEntry)), JsonObject.class);
            final String name = jsonObject.get("name").getAsString();

            module = new Module(name);
            module.setAbstraction(jsonObject.get("abstraction").getAsBoolean());

            if (!canAbstract && module.isAbstraction()) {
                LOGGER.error("The module " + name + " is a abstraction module! Couldn't directly depend on the module.");
                return null;
            }

            final JsonArray depends = jsonObject.getAsJsonArray("depends");
            for (JsonElement element : depends) {
                String depend = element.getAsString();
                Module dependModule = this.getByName(depend);
                if (dependModule == null) {
                    dependModule = this.registerByName(depend);
                    if (dependModule == null) {
                        LOGGER.error("Unable to find dependency module " + depend + " for " + name);
                        return null;
                    }
                    module.getDependModules().add(dependModule);
                    dependModule.addRef(); // add reference
                }
            }

            this.moduleByName.put(name, module);
            return module;
        } catch (Exception e) {
            LOGGER.error(e);

            if (module != null) {
                this.moduleByName.remove(module.getName());

                for (Module dependModule : module.getDependModules()) {
                    dependModule.removeRef();
                }
            }
        }

        return null;
    }

    public void unregister(@NonNull Module module) {
        this.moduleByName.remove(module.getName());

        for (Module dependModule : module.getDependModules()) {
            if (dependModule.removeRef() <= 0) {
                this.unregister(dependModule);
            }
        }
    }

}
