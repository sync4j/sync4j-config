package com.fathzer.sync4j.config;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fathzer.sync4j.Entry;
import com.fathzer.sync4j.FileProvider;
import com.fathzer.sync4j.Folder;
import com.fathzer.sync4j.file.LocalProvider;
import com.fathzer.sync4j.util.IOLambda.IOFunction;

class FolderLocationTest {
    private static final IOFunction<String, FileProvider> PROV_FUNC = r -> new LocalProvider(Paths.get(r));

    private static final class ThrowingFileProvider implements FileProvider {
        private final IOException exceptionToThrow;

        public ThrowingFileProvider(IOException exceptionToThrow) {
            this.exceptionToThrow = exceptionToThrow;
        }

        @Override
        public Entry get(String path) throws IOException {
            throw exceptionToThrow;
        }
    }

    @Test
    void testConstructor() {
        // Given
        final String path = "/test/path";
        
        // When
        FolderLocation location = new FolderLocation(PROV_FUNC, path);
        
        // Then
        assertNotNull(location);
        assertNull(location.suffix());
        assertSame(PROV_FUNC, location.provider());
        assertEquals(path, location.path());

        // When/Then
        assertThrows(NullPointerException.class, () -> new FolderLocation(null, path));
        // When/Then
        assertThrows(NullPointerException.class, () -> new FolderLocation(PROV_FUNC, null));
    }

    @Test
    void testWithSuffix_ValidSuffix() {
        // Given
        String basePath = "/test/path";
        String suffix = "/suffix";
        FolderLocation location = new FolderLocation(PROV_FUNC, basePath);
        
        // When
        FolderLocation newLocation = location.withSuffix(suffix);
        
        // Then
        assertNotSame(location, newLocation);
        assertSame(PROV_FUNC, newLocation.provider());
        assertEquals(basePath, newLocation.path());
        assertEquals(suffix, newLocation.suffix());
        // The original location (typically the one of the configuration) is not modified
        assertNull(location.suffix());
        assertEquals("/other", newLocation.withSuffix("/other").suffix());
        assertEquals(suffix, newLocation.suffix());
    }

    @Test
    void testWithMissingSlash() {
        // Given
        String parentPath = "/test/path";
        FolderLocation location = new FolderLocation(PROV_FUNC, parentPath);

        // When
        FolderLocation result = location.withSuffix("toto");
        
        // Then
        assertEquals("/toto", result.suffix());
    } 

    @Test
    void testTheSuffixCanNotGoOutOfTheLocation() {
        FolderLocation location = new FolderLocation(PROV_FUNC, "/test/path");
        for (String suffix : new String[] {"..", "/..", "../other", "a/../../other", "./a", "a/.", "a//b", "", "/", " ", "a/ /b", "..\\\\other", "a\0b"}) {
            assertThrows(IllegalArgumentException.class, () -> location.withSuffix(suffix), suffix);
        }
        // A trailing slash is tolerated, and names that only start with dots are names
        assertEquals("/a/b", location.withSuffix("a/b/").suffix());
        assertEquals("/.hidden/..x", location.withSuffix("/.hidden/..x").suffix());
    }

    @Test
    void testWithSuffix_NullSuffix() {
        // Given
        String basePath = "/test/path";
        FolderLocation location = new FolderLocation(PROV_FUNC, basePath);
        
        // When/Then
        assertThrows(NullPointerException.class, () -> location.withSuffix(null));
    }

    @Test
    void testEntry(@TempDir Path tempDir) throws IOException {
        // Given
        String path = tempDir.toString();
        FolderLocation location = new FolderLocation(PROV_FUNC, path);
        try (BufferedWriter writer = Files.newBufferedWriter(tempDir.resolve("test.txt"))) {
            writer.write("test");
        }
        
        // When
        Entry result = location.entry(false);
        
        // Then
        assertNotNull(result);
        assertTrue(result.isFolder(), "Should be a folder");
        assertEquals(List.of("test.txt"), result.asFolder().list().stream().map(Entry::getName).toList());
        try (BufferedReader reader = Files.newBufferedReader(tempDir.resolve("test.txt"))) {
            assertEquals("test", reader.readLine());
        }
        assertEquals(List.of("test.txt"), result.asFolder().list().stream().map(Entry::getName).toList());

        // Test with suffix
        // When/Then
        Path subdirPath = tempDir.resolve("subdir");
        Files.createDirectory(subdirPath);
        FolderLocation withSuffix = location.withSuffix("subdir");
        try (BufferedWriter writer = Files.newBufferedWriter(subdirPath.resolve("testInSubDir.txt"))) {
            writer.write("test in subdir");
        }
        result = withSuffix.entry(true);
        assertTrue(result.isFolder(), "Should be a folder");
        assertTrue(result.getFileProvider().isReadOnly(), "Source should be read-only");
        assertEquals(List.of("testInSubDir.txt"), result.asFolder().list().stream().map(Entry::getName).toList());
        try (BufferedReader reader = Files.newBufferedReader(subdirPath.resolve("testInSubDir.txt"))) {
            assertEquals("test in subdir", reader.readLine());
        }
    }

    @Test
    void testEntry_ProviderThrows() {
        // Given
        String errorMessage = "Provider error";
        ThrowingFileProvider fileProvider = new ThrowingFileProvider(new IOException(errorMessage));
        String path = "/test/path";
        FolderLocation location = new FolderLocation(r -> fileProvider, path);
        
        // When/Then
        IOException exception = assertThrows(IOException.class, () -> location.entry(false));
        assertEquals(errorMessage, exception.getMessage());
    }

    /** A provider function that fails the first times it is called with a given path. */
    private static final class FlakyOpening implements IOFunction<String, FileProvider> {
        private final String failingPath;
        private final AtomicInteger failures;
        private final AtomicInteger calls = new AtomicInteger();

        FlakyOpening(String failingPath, int failures) {
            this.failingPath = failingPath;
            this.failures = new AtomicInteger(failures);
        }

        @Override
        public FileProvider apply(String path) throws IOException {
            if (path.equals(failingPath)) {
                calls.incrementAndGet();
                if (failures.getAndDecrement() > 0) {
                    throw new IOException("Not visible yet: " + path);
                }
            }
            return new LocalProvider(Paths.get(path));
        }
    }

    private static FolderLocation location(IOFunction<String, FileProvider> function, Path path, int attempts) {
        return new FolderLocation(function, path.toString(), null, attempts, Duration.ofMillis(1));
    }

    @Test
    void testCreatedDestinationIsOpenedAgainIfNotVisibleYet(@TempDir Path tempDir) throws IOException {
        // The destination folder is created and, on an eventually consistent provider, it is not visible immediately
        final String root = tempDir.toString() + "/new/sub";
        final FlakyOpening function = new FlakyOpening(root, 2);
        final FolderLocation location = location(function, tempDir, 5).withSuffix("new/sub");

        Folder folder = location.entry(false);

        assertTrue(folder.isFolder());
        assertTrue(Files.isDirectory(tempDir.resolve("new/sub")), "The folder should have been created");
        assertEquals(3, function.calls.get(), "Two failures and one success were expected");
    }

    @Test
    void testCreatedDestinationGivesUp(@TempDir Path tempDir) {
        final String root = tempDir.toString() + "/new";
        final FlakyOpening function = new FlakyOpening(root, 100);
        final FolderLocation location = location(function, tempDir, 3).withSuffix("new");

        IOException exception = assertThrows(IOException.class, () -> location.entry(false));
        assertTrue(exception.getMessage().startsWith("Not visible yet"));
        assertEquals(3, function.calls.get());
    }

    @Test
    void testExistingDestinationIsNotRetried(@TempDir Path tempDir) throws IOException {
        // If the folder was not created by this call, a failure is definitive
        Files.createDirectory(tempDir.resolve("existing"));
        final String root = tempDir.toString() + "/existing";
        final FlakyOpening function = new FlakyOpening(root, 100);
        final FolderLocation location = location(function, tempDir, 5).withSuffix("existing");

        assertThrows(IOException.class, () -> location.entry(false));
        assertEquals(1, function.calls.get());
    }

    @Test
    void testSourceIsNotRetried(@TempDir Path tempDir) throws IOException {
        Files.createDirectory(tempDir.resolve("source"));
        final String root = tempDir.toString() + "/source";
        final FlakyOpening function = new FlakyOpening(root, 100);
        final FolderLocation location = location(function, tempDir, 5).withSuffix("source");

        assertThrows(IOException.class, () -> location.entry(true));
        assertEquals(1, function.calls.get());
    }

    @Test
    void testMissingRootIsAnIOException(@TempDir Path tempDir) {
        // LocalProvider rejects a root that does not exist with an unchecked exception
        final FolderLocation location = new FolderLocation(PROV_FUNC, tempDir.resolve("missing").toString());

        final IOException e = assertThrows(IOException.class, () -> location.entry(true));

        assertTrue(e.getMessage().contains("missing"), e.getMessage());
        assertInstanceOf(IllegalArgumentException.class, e.getCause());
    }
}
