/*
 * MIT License
 *
 * Copyright (c) 2021 Imanity
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.fairyproject.bukkit;

import io.fairyproject.Debug;
import io.fairyproject.ExtendedClassLoader;
import io.fairyproject.FairyPlatform;
import io.fairyproject.PlatformType;
import io.fairyproject.bukkit.events.PostServicesInitialEvent;
import io.fairyproject.bukkit.impl.BukkitPluginHandler;
import io.fairyproject.bukkit.impl.BukkitTaskScheduler;
import io.fairyproject.bukkit.listener.FilteredListener;
import io.fairyproject.bukkit.listener.ListenerSubscription;
import io.fairyproject.bukkit.listener.events.Events;
import io.fairyproject.bukkit.logger.Log4jLogger;
import io.fairyproject.bukkit.mc.BukkitMCInitializer;
import io.fairyproject.bukkit.reflection.MinecraftReflection;
import io.fairyproject.bukkit.util.JavaPluginUtil;
import io.fairyproject.bukkit.util.SpigotUtil;
import io.fairyproject.container.ContainerContext;
import io.fairyproject.container.PreInitialize;
import io.fairyproject.container.collection.ContainerObjCollector;
import io.fairyproject.log.Log;
import io.fairyproject.mc.MCInitializer;
import io.fairyproject.plugin.Plugin;
import io.fairyproject.plugin.PluginManager;
import io.fairyproject.task.ITaskScheduler;
import io.fairyproject.util.terminable.TerminableConsumer;
import io.fairyproject.util.terminable.composite.CompositeTerminable;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import org.bukkit.Bukkit;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;

public class FairyBukkitPlatform extends FairyPlatform implements TerminableConsumer {

    public static FairyBukkitPlatform INSTANCE;
    public static JavaPlugin PLUGIN = JavaPluginUtil.getProvidingPlugin(FairyBukkitPlatform.class);
    public static BukkitAudiences AUDIENCES;

    private final ExtendedClassLoader classLoader;
    private final File dataFolder;
    private final CompositeTerminable compositeTerminable;

    @NotNull
    @Override
    public <T extends AutoCloseable> T bind(@NotNull T terminable) {
        return this.compositeTerminable.bind(terminable);
    }

    public FairyBukkitPlatform(File dataFolder) {
        FairyPlatform.INSTANCE = this;
        this.dataFolder = dataFolder;
        this.compositeTerminable = CompositeTerminable.create();
        this.classLoader = new ExtendedClassLoader(this.getClass().getClassLoader());

        PluginManager.initialize(new BukkitPluginHandler());
        // Use log4j for bukkit platform
        if (!Debug.UNIT_TEST)
            Log.set(new Log4jLogger());
    }

    @Override
    public void load(Plugin plugin) {
        super.load(plugin);

        MinecraftReflection.init();
        this.createMCInitializer().apply();
    }

    @Override
    public void enable() {
        AUDIENCES = BukkitAudiences.create(PLUGIN);

        SpigotUtil.init();
        super.enable();
    }

    @PreInitialize
    public void onPreInitialize() {
        ContainerContext.get().objectCollectorRegistry().add(ContainerObjCollector.create()
                .withFilter(ContainerObjCollector.inherits(Listener.class))
                .withFilter(ContainerObjCollector.inherits(FilteredListener.class).negate())
                .withAddHandler(containerObj -> {
                    Listener listener = (Listener) containerObj.instance();
                    ListenerSubscription subscription = Events.subscribe(listener);

                    containerObj.bind(subscription);
                }));
    }

    @Override
    public void onPostServicesInitial() {
        Events.call(new PostServicesInitialEvent());
    }

    public MCInitializer createMCInitializer() {
        return new BukkitMCInitializer();
    }

    @Override
    public void saveResource(String name, boolean replace) {
        PLUGIN.saveResource(name, replace);
    }

    @Override
    public ExtendedClassLoader getClassloader() {
        return this.classLoader;
    }

    @Override
    public File getDataFolder() {
        return this.dataFolder;
    }

    @Override
    public void shutdown() {
        Bukkit.shutdown();
    }

    @Override
    public boolean isRunning() {
        return true;
    }

    @Override
    public boolean isMainThread() {
        return Bukkit.isPrimaryThread();
    }

    @Override
    public ITaskScheduler createTaskScheduler() {
        return new BukkitTaskScheduler();
    }

    @Override
    public PlatformType getPlatformType() {
        return PlatformType.BUKKIT;
    }
}
