package com.fathzer.sync4j.config;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fathzer.sync4j.FileProviderFactory;
import com.fathzer.sync4j.file.LocalProviderFactory;

class ProviderRegistryTest {
    private static final String JAR_FACTORY = """
            package jarplugin;
            public class JarProviderFactory implements com.fathzer.sync4j.FileProviderFactory<Void> {
              public String type() { return "jar-memory"; }
              public Class<Void> configType() { return Void.class; }
              public com.fathzer.sync4j.util.IOLambda.IOFunction<String, com.fathzer.sync4j.FileProvider> create(Void config) {
                return root -> new com.fathzer.sync4j.memory.MemoryFileProvider();
              }
            }
            """;

    @TempDir
    Path tmp;

    private Path pluginJar(String name) throws IOException {
        final Path directory = Files.createDirectories(tmp.resolve("plugins"));
        final Path jar = directory.resolve(name);
        TestJars.build(jar, Map.of("jarplugin.JarProviderFactory", JAR_FACTORY), Map.of(FileProviderFactory.class.getName(), List.of("jarplugin.JarProviderFactory")));
        return jar;
    }

    @Test
    void testDefaults() throws IOException {
        final ProviderRegistry registry = ProviderRegistry.withDefaults();

        // The providers of sync4j are discovered on the classpath, like the factories of the other libraries
        assertEquals("local", registry.get("local").type());
        assertEquals("memory", registry.get("memory").type());
        // This one is declared in the META-INF/services of the tests
        assertEquals("test-memory", registry.get("test-memory").type());
        final IOException unknown = assertThrows(IOException.class, () -> registry.get("ftp"));
        assertEquals("Unknown provider: ftp", unknown.getMessage());
    }

    @Test
    void testRegister() throws IOException {
        final ProviderRegistry registry = new ProviderRegistry();
        assertThrows(IOException.class, () -> registry.get("local"));

        final LocalProviderFactory factory = new LocalProviderFactory();
        assertSame(registry, registry.register(factory));
        assertSame(factory, registry.get("local"));

        // The last registered factory of a type wins
        final LocalProviderFactory other = new LocalProviderFactory();
        registry.register(other);
        assertSame(other, registry.get("local"));
    }

    @Test
    void testJarDirectory() throws IOException {
        pluginJar("a-plugin.jar");

        assertThrows(IOException.class, () -> ProviderRegistry.withDefaults().get("jar-memory"));
        assertEquals("jar-memory", ProviderRegistry.withDefaults(tmp.resolve("plugins")).get("jar-memory").type());
    }

    @Test
    void testMissingDirectoryAndBrokenJarsAreIgnored() throws IOException {
        final Path directory = Files.createDirectories(tmp.resolve("plugins"));
        Files.writeString(directory.resolve("0-broken.jar"), "Not a jar");
        Files.writeString(directory.resolve("readme.txt"), "Not a jar either");
        TestJars.build(directory.resolve("1-missing-class.jar"), Map.of(), Map.of(FileProviderFactory.class.getName(), List.of("does.not.Exist")));
        TestJars.build(directory.resolve("2-wrong-type.jar"), Map.of("jarplugin.NotAFactory", "package jarplugin; public class NotAFactory {}"),
                Map.of(FileProviderFactory.class.getName(), List.of("jarplugin.NotAFactory")));
        pluginJar("3-valid.jar");

        // The valid jar is found, despite the other ones
        assertEquals("jar-memory", ProviderRegistry.withDefaults(directory).get("jar-memory").type());
        // A directory that does not exist is ignored
        assertEquals("local", ProviderRegistry.withDefaults(tmp.resolve("missing")).get("local").type());
    }

    @Test
    void testClasspathFactoriesComeBeforeTheJarOnes() throws IOException {
        pluginJar("plugin.jar");

        @SuppressWarnings("rawtypes")
        final List<FileProviderFactory> factories = PluginDiscovery.discover(FileProviderFactory.class, tmp.resolve("plugins"));
        final List<String> types = factories.stream().map(FileProviderFactory::type).toList();

        // The ones of sync4j (local and memory), the one of the tests and, last, the one of the jar of the directory
        assertEquals(Set.of("local", "memory", "test-memory", "jar-memory"), Set.copyOf(types));
        assertEquals(4, types.size());
        assertEquals("jar-memory", types.get(types.size() - 1));
    }
}
