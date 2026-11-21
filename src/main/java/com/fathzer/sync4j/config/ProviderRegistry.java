package com.fathzer.sync4j.config;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import com.fathzer.sync4j.FileProviderFactory;

/**
 * The provider factories that can be used by a configuration.
 */
public class ProviderRegistry {
    private final Map<String, FileProviderFactory<?>> factories = new HashMap<>();

    /**
     * Creates a registry that contains the factories found on the classpath (see {@link PluginDiscovery}): the ones of the providers of sync4j (local, memory),
     * and the ones of the libraries that ship their factory.
     * @return a new registry
     */
    public static ProviderRegistry withDefaults() {
        return withDefaults(null);
    }

    /**
     * Creates a registry that contains the factories found on the classpath and in the jar files of a directory (see {@link PluginDiscovery}).
     * <br>A provider module that ships its factory (as sync4j and sync4j-pcloud do) is available as soon as its jar is on the classpath, or in the directory.
     * @param pluginsDirectory a directory that contains jar files (null if there is none)
     * @return a new registry
     */
    @SuppressWarnings("rawtypes")
    public static ProviderRegistry withDefaults(Path pluginsDirectory) {
        final ProviderRegistry registry = new ProviderRegistry();
        for (FileProviderFactory<?> factory : (Iterable<FileProviderFactory>) PluginDiscovery.discover(FileProviderFactory.class, pluginsDirectory)) {
            registry.register(factory);
        }
        return registry;
    }

    /**
     * Registers a factory.
     * @param factory the factory. It replaces the previous one registered for the same type
     * @return this
     */
    public ProviderRegistry register(FileProviderFactory<?> factory) {
        factories.put(Objects.requireNonNull(factory.type()), factory);
        return this;
    }

    /**
     * Gets a factory.
     * @param type the type of provider
     * @return the factory
     * @throws IOException if no factory is registered for this type
     */
    // False positive: the registry contains factories of any configuration type, the wildcard tells the truth.
    // A type parameter chosen by the caller would be an unchecked cast hidden in the signature.
    @SuppressWarnings("java:S1452")
    public FileProviderFactory<?> get(String type) throws IOException {
        final FileProviderFactory<?> factory = factories.get(type);
        if (factory == null) {
            throw new IOException("Unknown provider: " + type);
        }
        return factory;
    }
}
