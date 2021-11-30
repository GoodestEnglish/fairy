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

package io.fairyproject.container;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import io.fairyproject.container.controller.SubscribeEventContainerController;
import io.fairyproject.container.object.*;
import io.fairyproject.container.object.parameter.ContainerParameterDetailsMethod;
import io.fairyproject.container.exception.ServiceAlreadyExistsException;
import io.fairyproject.event.EventBus;
import io.fairyproject.event.impl.PostServiceInitialEvent;
import io.fairyproject.plugin.Plugin;
import io.fairyproject.util.exceptionally.ThrowingRunnable;
import lombok.NonNull;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import io.fairyproject.Fairy;
import io.fairyproject.container.controller.AutowiredContainerController;
import io.fairyproject.container.controller.ContainerController;
import io.fairyproject.plugin.PluginListenerAdapter;
import io.fairyproject.plugin.PluginManager;
import io.fairyproject.reflect.ReflectLookup;
import io.fairyproject.util.NonNullArrayList;
import io.fairyproject.util.SimpleTiming;
import org.reflections.util.ClasspathHelper;
import org.reflections.util.ConfigurationBuilder;
import org.reflections.util.FilterBuilder;

import java.lang.reflect.*;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

public class ContainerContext {

    public static boolean SHOW_LOGS = false;
    public static ContainerContext INSTANCE;
    public static final int PLUGIN_LISTENER_PRIORITY = 100;

    /**
     * Logging
     */
    protected static final Logger LOGGER = LogManager.getLogger(ContainerContext.class);
    protected static void log(String msg, Object... replacement) {
        if (SHOW_LOGS) {
            LOGGER.info("[BeanContext] " + String.format(msg, replacement));
        }
    }
    protected static SimpleTiming logTiming(String msg) {
        return SimpleTiming.create(time -> log("Ended %s - took %d ms", msg, time));
    }

    private ContainerController[] controllers;

    /**
     * Lookup Storages
     */
    private final Map<Class<?>, ContainerObject> containerByType = new ConcurrentHashMap<>();

    /**
     * NOT THREAD SAFE
     */
    private final List<ContainerObject> sortedBeans = new ArrayList<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /**
     * Initializing Method for Bean Context
     */
    public void init() {
        INSTANCE = this;

        // TODO: annotated registration?
        this.controllers = Arrays.asList(

                new AutowiredContainerController(),
                new SubscribeEventContainerController()

        ).toArray(new ContainerController[0]);

        this.registerBean(new SimpleContainerObject(this, this.getClass()));
        log("BeanContext has been registered as bean.");

        ComponentRegistry.registerComponentHolders();
        try {
            this.scanClasses()
                    .name("framework")
                    .mainClassloader(ContainerContext.class.getClassLoader())
                    .classPath("io.fairyproject")
                    .scan();
        } catch (Throwable throwable) {
            LOGGER.error("Error while scanning classes for framework", throwable);
            Fairy.getPlatform().shutdown();
            return;
        }

        if (PluginManager.isInitialized()) {
            log("Find PluginManager, attempt to register Plugin Listeners");

            PluginManager.INSTANCE.registerListener(new PluginListenerAdapter() {

                @Override
                public void onPluginInitial(Plugin plugin) {

                }

                @Override
                public void onPluginEnable(Plugin plugin) {
                    final Class<? extends Plugin> aClass = plugin.getClass();
                    ContainerObject containerObject = new SimpleContainerObject(plugin, aClass);

                    try {
                        containerObject.bindWith(plugin);
                        registerBean(containerObject, false);
                        log("Plugin " + plugin.getName() + " has been registered as bean.");
                    } catch (Throwable throwable) {
                        LOGGER.error("An error occurs while registering plugin", throwable);
                        try {
                            plugin.close();
                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }
                        return;
                    }

                    try {
                        final List<String> classPaths = findClassPaths(aClass);
                        classPaths.add(plugin.getDescription().getShadedPackage());
                        scanClasses()
                                .name(plugin.getName())
                                .mainClassloader(plugin.getPluginClassLoader())
                                .classPath(classPaths)
                                .included(containerObject)
                                .scan();
                    } catch (Throwable throwable) {
                        LOGGER.error("An error occurs while handling scanClasses()", throwable);
                        try {
                            plugin.close();
                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }
                        return;
                    }
                }

                @Override
                public void onPluginDisable(Plugin plugin) {
                    Collection<ContainerObject> containerObjectList = findDetailsBindWith(plugin);
                    try {
                        lifeCycle(LifeCycle.PRE_DESTROY, containerObjectList);
                    } catch (Throwable throwable) {
                        LOGGER.error(throwable);
                    }

                    containerObjectList.forEach(ContainerObject::closeAndReportException);

                    try {
                        lifeCycle(LifeCycle.POST_DESTROY, containerObjectList);
                    } catch (Throwable throwable) {
                        LOGGER.error(throwable);
                    }
                }

                @Override
                public int priority() {
                    return PLUGIN_LISTENER_PRIORITY;
                }
            });
        }

        Fairy.getPlatform().onPostServicesInitial();
        EventBus.call(new PostServiceInitialEvent());
    }

    /**
     * Shutdown Method for Bean Context
     */
    public void stop() {
        List<ContainerObject> detailsList = Lists.newArrayList(this.sortedBeans);
        Collections.reverse(detailsList);

        lifeCycle(LifeCycle.PRE_DESTROY, detailsList);
        for (ContainerObject details : detailsList) {
            log("Bean " + details.getType() + " Disabled, due to framework being disabled.");

            details.onDisable();
            unregisterBean(details);
        }
        lifeCycle(LifeCycle.POST_DESTROY, detailsList);
    }

    public void disableBeanUnchecked(Class<?> type) {
        this.disableBeanUnchecked(this.getBeanDetails(type));
    }

    public void disableBeanUnchecked(ContainerObject containerObject) {
        ThrowingRunnable.unchecked(() -> this.disableBean(containerObject)).run();
    }

    public void disableBean(Class<?> type) throws InvocationTargetException, IllegalAccessException {
        this.disableBean(this.getBeanDetails(type));
    }

    public void disableBean(ContainerObject containerObject) throws InvocationTargetException, IllegalAccessException {
        containerObject.lifeCycle(LifeCycle.PRE_DESTROY);
        containerObject.onDisable();
        this.unregisterBean(containerObject);
        containerObject.lifeCycle(LifeCycle.POST_DESTROY);
    }

    public ContainerObject registerBean(ContainerObject containerObject) {
        return this.registerBean(containerObject, true);
    }

    public ContainerObject registerBean(ContainerObject containerObject, boolean sort) {
        this.containerByType.put(containerObject.getType(), containerObject);
        if (sort) {
            this.sortedBeans.add(containerObject);
        }

        return containerObject;
    }

    public Collection<ContainerObject> unregisterBean(Class<?> type) {
        return this.unregisterBean(this.getBeanDetails(type));
    }

    public Collection<ContainerObject> unregisterBean(@NonNull ContainerObject containerObject) {
        this.containerByType.remove(containerObject.getType());

        this.lock.writeLock().lock();
        this.sortedBeans.remove(containerObject);
        this.lock.writeLock().unlock();

        final ImmutableList.Builder<ContainerObject> builder = ImmutableList.builder();

        // Unregister Child Dependency
        for (Class<?> child : containerObject.getChildren()) {
            ContainerObject childDetails = this.getBeanDetails(child);

            builder.add(childDetails);
            builder.addAll(this.unregisterBean(childDetails));
        }

        // Remove Children from dependencies
        for (Class<?> dependency : containerObject.getAllDependencies()) {
            ContainerObject dependDetails = this.getBeanDetails(dependency);

            if (dependDetails != null) {
                dependDetails.removeChildren(containerObject.getType());
            }
        }

        return builder.build();
    }

    public ContainerObject getBeanDetails(Class<?> type) {
        return this.containerByType.get(type);
    }

    public Object getBean(@NonNull Class<?> type) {
        ContainerObject details = this.getBeanDetails(type);
        if (details == null) {
            return null;
        }
        return details.getInstance();
    }

    public boolean isRegisteredBeans(Class<?>... beans) {
        for (Class<?> bean : beans) {
            ContainerObject dependencyDetails = this.getBeanDetails(bean);
            if (dependencyDetails == null || dependencyDetails.getInstance() == null) {
                return false;
            }
        }
        return true;
    }

    public boolean isBean(Class<?> beanClass) {
        return this.containerByType.containsKey(beanClass);
    }

    public boolean isBean(Object bean) {
        return this.isBean(bean.getClass());
    }

    public Collection<ContainerObject> findDetailsBindWith(Plugin plugin) {
        return this.containerByType.values()
                .stream()
                .filter(beanDetails -> beanDetails.isBind() && beanDetails.getBindPlugin().equals(plugin))
                .collect(Collectors.toList());
    }

    /**
     * Registration
     */

    public ComponentContainerObject registerComponent(Object instance, String prefix, Class<?> type, ComponentHolder componentHolder) throws InvocationTargetException, IllegalAccessException {
        Component component = type.getAnnotation(Component.class);
        if (component == null) {
            throw new IllegalArgumentException("The type " + type.getName() + " doesn't have Component annotation!");
        }

        ServiceDependency serviceDependency = type.getAnnotation(ServiceDependency.class);
        if (serviceDependency != null) {
            for (Class<?> dependency : serviceDependency.value()) {
                if (!this.isRegisteredBeans(dependency)) {
                    switch (serviceDependency.type()) {
                        case FORCE:
                            LOGGER.error("Couldn't find the dependency " + dependency + " for " + type.getSimpleName() + "!");
                        case SUB_DISABLE:
                            return null;
                        case SUB:
                            break;
                    }
                }
            }
        }

        ComponentContainerObject containerObject = new ComponentContainerObject(type, instance, componentHolder);
        containerObject.lifeCycle(LifeCycle.CONSTRUCT);
        if (!containerObject.shouldInitialize()) {
            return null;
        }

        this.registerBean(containerObject);
        this.attemptBindPlugin(containerObject);

        try {
            containerObject.lifeCycle(LifeCycle.PRE_INIT);
        } catch (Throwable throwable) {
            LOGGER.error(throwable);
        }
        return containerObject;
    }

    private void attemptBindPlugin(ContainerObject containerObject) {
        if (PluginManager.isInitialized()) {
            Plugin plugin = PluginManager.INSTANCE.getPluginByClass(containerObject.getType());

            if (plugin != null) {
                containerObject.bindWith(plugin);

                log("Bean " + containerObject.getType() + " is now bind with plugin " + plugin.getName());
            }
        }
    }

    public void lifeCycle(LifeCycle lifeCycle, Collection<ContainerObject> containerObjectList) {
        for (ContainerObject containerObject : containerObjectList) {
            try {
                containerObject.lifeCycle(lifeCycle);
            } catch (Throwable throwable) {
                LOGGER.error("An error occurs while calling life cycle method", throwable);
            }
        }
    }

    public List<String> findClassPaths(Class<?> plugin) {
        ClasspathScan annotation = plugin.getAnnotation(ClasspathScan.class);

        if (annotation != null) {
            return Lists.newArrayList(annotation.value());
        }

        return Lists.newArrayList();
    }

    public ClassPathScanner scanClasses() {
        return new ClassPathScanner();
    }

    public class ClassPathScanner {

        private String prefix = "";
        private String scanName;
        private final List<String> classPaths = new ArrayList<>();
        private final List<String> excludedPackages = new ArrayList<>();

        private ClassLoader mainClassLoader;
        private final List<ClassLoader> otherClassLoaders = new ArrayList<>();

        private final List<ContainerObject> included = new ArrayList<>();

        public ClassPathScanner prefix(String prefix) {
            this.prefix = prefix;
            return this;
        }

        public ClassPathScanner name(String name) {
            this.scanName = name;
            return this;
        }

        public ClassPathScanner classPath(String... classPath) {
            this.classPaths.addAll(Arrays.asList(classPath));
            return this;
        }

        public ClassPathScanner classPath(Collection<String> classPath) {
            this.classPaths.addAll(classPath);
            return this;
        }

        public ClassPathScanner excludePackage(String... classPath) {
            this.excludedPackages.addAll(Arrays.asList(classPath));
            return this;
        }

        public ClassPathScanner excludePackage(Collection<String> classPath) {
            this.excludedPackages.addAll(classPath);
            return this;
        }

        public ClassPathScanner mainClassloader(ClassLoader classLoader) {
            this.mainClassLoader = classLoader;
            return this;
        }

        public ClassPathScanner classLoader(ClassLoader... classLoaders) {
            this.otherClassLoaders.addAll(Arrays.asList(classLoaders));
            return this;
        }

        public ClassPathScanner classLoader(Collection<ClassLoader> classLoaders) {
            this.otherClassLoaders.addAll(classLoaders);
            return this;
        }

        public ClassPathScanner included(ContainerObject... beanDetails) {
            this.included.addAll(Arrays.asList(beanDetails));
            return this;
        }

        public ClassPathScanner included(Collection<ContainerObject> beanDetails) {
            this.included.addAll(beanDetails);
            return this;
        }

        public List<ContainerObject> scan() throws Exception {
            log("Start scanning beans for %s with packages [%s]...", scanName, String.join(" ", classPaths));

            // Build the instance for Reflection Lookup
            ReflectLookup reflectLookup;
            try (SimpleTiming ignored = logTiming("Reflect Lookup building")) {
                final ConfigurationBuilder configurationBuilder = new ConfigurationBuilder();

                FilterBuilder filterBuilder = new FilterBuilder();
                List<URL> urls = new ArrayList<>();
                for (String classPath : classPaths) {
                    // Only search package in the main class loader
                    urls.addAll(ClasspathHelper.forPackage(classPath, mainClassLoader));
                    filterBuilder.includePackage(classPath);
                }

                for (String classPath : this.excludedPackages) {
                    filterBuilder.excludePackage(classPath);
                }
                configurationBuilder.setUrls(urls);

                final ArrayList<ClassLoader> classLoaders = new ArrayList<>(otherClassLoaders);
                classLoaders.add(mainClassLoader);
                configurationBuilder.addClassLoaders(classLoaders);

                configurationBuilder.filterInputsBy(filterBuilder);
                reflectLookup = new ReflectLookup(configurationBuilder);
            }

            // Scanning through the JAR to see every Service Bean can be registered
            List<ContainerObject> containerObjectList;
            try (SimpleTiming ignored = logTiming("Scanning Beans")) {
                containerObjectList = new NonNullArrayList<>(included);

                for (Class<?> type : reflectLookup.findAnnotatedClasses(Service.class)) {
                    try {
                        Service service = type.getAnnotation(Service.class);
                        Preconditions.checkNotNull(service, "The type " + type.getName() + " doesn't have @Service annotation! " + Arrays.toString(type.getAnnotations()));

                        if (getBeanDetails(type) == null) {
                            ServiceContainerObject beanDetails = new ServiceContainerObject(type, service.depends());

                            log("Found " + beanDetails + " with type " + type.getSimpleName() + ", Registering it as bean...");

                            attemptBindPlugin(beanDetails);
                            registerBean(beanDetails, false);

                            containerObjectList.add(beanDetails);
                        } else {
                            new ServiceAlreadyExistsException(type).printStackTrace();
                        }
                    } catch (Throwable throwable) {
                        throw new IllegalStateException("An exception has been thrown while scanning bean for " + type.getName(), throwable);
                    }
                }
            }

            // Scanning methods that registers bean
            try (SimpleTiming ignored = logTiming("Scanning Bean Method")) {
                for (Method method : reflectLookup.findAnnotatedStaticMethods(Register.class)) {
                    if (method.getReturnType() == void.class) {
                        new IllegalArgumentException("The Method " + method + " has annotated @Bean but no return type!").printStackTrace();
                    }
                    ContainerParameterDetailsMethod detailsMethod = new ContainerParameterDetailsMethod(method, ContainerContext.this);
                    List<Class<?>> dependencies = new ArrayList<>();
                    for (Parameter type : detailsMethod.getParameters()) {
                        ContainerObject details = getBeanDetails(type.getType());
                        if (details != null) {
                            dependencies.add(details.getType());
                        }
                    }
                    final Object instance = detailsMethod.invoke(null, ContainerContext.this);

                    Register register = method.getAnnotation(Register.class);
                    if (register == null) {
                        continue;
                    }

                    Class<?> objectType = detailsMethod.returnType();
                    if (register.as() != Void.class) {
                        objectType = register.as();
                    }

                    if (getBeanDetails(objectType) == null) {
                        ContainerObject containerObject = new RelativeContainerObject(objectType, instance, dependencies.toArray(new Class<?>[0]));

                        log("Found " + objectType + " with type " + instance.getClass().getSimpleName() + ", Registering it as bean...");

                        attemptBindPlugin(containerObject);
                        registerBean(containerObject, false);

                        containerObjectList.add(containerObject);
                    } else {
                        new ServiceAlreadyExistsException(objectType).printStackTrace();
                    }
                }
            }

            // Load Beans in Dependency Tree Order
            try (SimpleTiming ignored = logTiming("Initializing ContainerObject")) {
                containerObjectList = loadInOrder(containerObjectList);
            } catch (Throwable throwable) {
                LOGGER.error("An error occurs while handling loadInOrder()", throwable);
            }

            // Unregistering Beans that returns false in shouldInitialize
            try (SimpleTiming ignored = logTiming("Unregistering Disabled ContainerObject")) {
                sortedBeans.addAll(containerObjectList);

                for (ContainerObject containerObject : ImmutableList.copyOf(containerObjectList)) {
                    if (!containerObjectList.contains(containerObject)) {
                        continue;
                    }
                    try {
                        if (!containerObject.shouldInitialize()) {
                            log("Unregistering " + containerObject + " due to it cancelled to register");

                            containerObjectList.remove(containerObject);
                            for (ContainerObject details : unregisterBean(containerObject)) {
                                log("Unregistering " + containerObject + " due to it dependency unregistered");

                                containerObjectList.remove(details);
                            }
                        }
                    } catch (InvocationTargetException | IllegalAccessException e) {
                        LOGGER.error(e);
                        unregisterBean(containerObject);
                    }
                }
            }

            // Call @PreInitialize methods for bean
            try (SimpleTiming ignored = logTiming("LifeCycle PRE_INIT")) {
                lifeCycle(LifeCycle.PRE_INIT, containerObjectList);
            }

            // Scan Components
            try (SimpleTiming ignored = logTiming("Scanning Components")) {
                containerObjectList.addAll(ComponentRegistry.scanComponents(ContainerContext.this, reflectLookup, prefix));
            }

            // Inject @Autowired fields for beans
            try (SimpleTiming ignored = logTiming("Injecting Beans")) {
                for (ContainerObject containerObject : containerObjectList) {
                    for (ContainerController controller : controllers) {
                        try {
                            controller.applyContainerObject(containerObject);
                        } catch (Throwable throwable) {
                            LOGGER.warn("An error occurs while apply controller for " + containerObject.getType(), throwable);
                        }
                    }
                }
            }

            // Inject @Autowired static fields
            try (SimpleTiming ignored = logTiming("Injecting Static Autowired Fields")) {
                for (Field field : reflectLookup.findAnnotatedStaticFields(Autowired.class)) {
                    if (!Modifier.isStatic(field.getModifiers())) {
                        continue;
                    }

                    AutowiredContainerController.INSTANCE.applyField(field, null);
                }
            }

            // Call onEnable() for Components
            try (SimpleTiming ignored = logTiming("Call onEnable() for Components")) {
                containerObjectList.forEach(ContainerObject::onEnable);
            }

            // Call @PostInitialize
            try (SimpleTiming ignored = logTiming("LifeCycle POST_INIT")) {
                lifeCycle(LifeCycle.POST_INIT, containerObjectList);
            }

            return containerObjectList;
        }

        private List<ContainerObject> loadInOrder(List<ContainerObject> containerObjectList) {
            Map<Class<?>, ContainerObject> unloaded = new HashMap<>();
            for (ContainerObject containerObject : containerObjectList) {
                unloaded.put(containerObject.getType(), containerObject);
                if (containerObject instanceof ServiceContainerObject) {
                    ((ServiceContainerObject) containerObject).setupConstruction(ContainerContext.this);
                }
            }
            // Remove Services without valid dependency
            Iterator<Map.Entry<Class<?>, ContainerObject>> removeIterator = unloaded.entrySet().iterator();
            while (removeIterator.hasNext()) {
                Map.Entry<Class<?>, ContainerObject> entry = removeIterator.next();
                ContainerObject containerObject = entry.getValue();
                if (!containerObject.hasDependencies()) {
                    continue;
                }
                for (Map.Entry<ServiceDependencyType, List<Class<?>>> allDependency : containerObject.getDependencyEntries()) {
                    final ServiceDependencyType type = allDependency.getKey();
                    search: for (Class<?> dependency : allDependency.getValue()) {
                        ContainerObject dependencyDetails = ContainerContext.this.getBeanDetails(dependency);
                        if (dependencyDetails == null) {
                            switch (type) {
                                case FORCE:
                                    LOGGER.error("Couldn't find the dependency " + dependency + " for " + containerObject.getType().getSimpleName() + "!");
                                    removeIterator.remove();
                                    break search;
                                case SUB_DISABLE:
                                    removeIterator.remove();
                                    break search;
                                case SUB:
                                    break;
                            }
                            // Prevent dependency each other
                        } else {
                            if (dependencyDetails.hasDependencies()
                                    && dependencyDetails.getAllDependencies().contains(containerObject.getType())) {
                                LOGGER.error("Target " + containerObject.getType().getSimpleName() + " and " + dependency + " depend to each other!");
                                removeIterator.remove();

                                unloaded.remove(dependency);
                                break;
                            }

                            dependencyDetails.addChildren(containerObject.getType());
                        }
                    }
                }
            }
            // Continually loop until all dependency found and loaded
            List<ContainerObject> sorted = new NonNullArrayList<>();
            while (!unloaded.isEmpty()) {
                Iterator<Map.Entry<Class<?>, ContainerObject>> iterator = unloaded.entrySet().iterator();
                while (iterator.hasNext()) {
                    Map.Entry<Class<?>, ContainerObject> entry = iterator.next();
                    ContainerObject containerObject = entry.getValue();
                    boolean missingDependencies = false;
                    for (Map.Entry<ServiceDependencyType, List<Class<?>>> dependencyEntry : containerObject.getDependencyEntries()) {
                        final ServiceDependencyType type = dependencyEntry.getKey();
                        for (Class<?> dependency : dependencyEntry.getValue()) {
                            ContainerObject dependencyDetails = ContainerContext.this.getBeanDetails(dependency);
                            if (dependencyDetails != null && dependencyDetails.getInstance() != null) {
                                continue;
                            }
                            if (type == ServiceDependencyType.SUB && !unloaded.containsKey(dependency)) {
                                continue;
                            }
                            missingDependencies = true;
                        }
                    }
                    if (!missingDependencies) {
                        if (containerObject instanceof ServiceContainerObject) {
                            ((ServiceContainerObject) containerObject).build(ContainerContext.this);
                        }
                        containerObject.lifeCycle(LifeCycle.CONSTRUCT);
                        sorted.add(containerObject);
                        iterator.remove();
                    }
                }
            }
            return sorted;
        }

    }

}
