package com.fathzer.sync4j.config;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.Objects;

import com.fathzer.sync4j.Entry;
import com.fathzer.sync4j.FileProvider;
import com.fathzer.sync4j.Folder;
import com.fathzer.sync4j.util.IOLambda.IOFunction;

/**
 * A folder on a file provider, with an optional suffix (a sub folder of the location, typically requested by the user for one synchronization).
 * <br>Instances are immutable: {@link #withSuffix(String)} returns a new instance and never changes the location of the configuration.
 */
public class FolderLocation {
    private static final int DEFAULT_OPEN_ATTEMPTS = 5;
    private static final Duration DEFAULT_OPEN_DELAY = Duration.ofMillis(200);

    private final IOFunction<String, FileProvider> provider;
    private final String path;
    private final String suffix;
    private final int openAttempts;
    private final Duration openDelay;

    /**
     * Constructor.
     * @param provider the function that opens the provider whose root is a path
     * @param path the path of the location
     */
    public FolderLocation(IOFunction<String, FileProvider> provider, String path) {
        this(provider, path, null, DEFAULT_OPEN_ATTEMPTS, DEFAULT_OPEN_DELAY);
    }

    /**
     * Constructor.
     * @param openAttempts the maximum number of attempts to open a destination folder that was just created. On an eventually consistent storage, it may not be visible immediately.
     * @param openDelay the delay before the second attempt (it doubles at each new attempt)
     */
    FolderLocation(IOFunction<String, FileProvider> provider, String path, String suffix, int openAttempts, Duration openDelay) {
        this.provider = Objects.requireNonNull(provider);
        this.path = Objects.requireNonNull(path);
        this.suffix = suffix;
        this.openAttempts = openAttempts;
        this.openDelay = openDelay;
    }

    IOFunction<String, FileProvider> provider() {
        return provider;
    }

    /**
     * Gets the path of this location.
     * @return the path
     */
    public String path() {
        return path;
    }

    /**
     * Gets the suffix of this location.
     * @return the suffix (starts with a slash), null if there is none
     */
    public String suffix() {
        return suffix;
    }

    /**
     * Returns a location with the same provider and path, but with a suffix.
     * <br>The suffix designates a sub folder of the location: it can't go out of it. It often comes from a less trusted source than the configuration
     * (a request of a user, for instance): a suffix such as <code>../other</code> would let it synchronize (and delete) another folder.
     * @param suffix the suffix, a relative path (a leading slash is added if missing, a trailing one is removed)
     * @return a new location, this location is not modified
     * @throws IllegalArgumentException if the suffix is not a sub folder: empty, or with an empty name, <code>.</code>, <code>..</code>, a backslash
     * (a separator on Windows) or a null character
     */
    public FolderLocation withSuffix(String suffix) {
        return new FolderLocation(provider, path, checkSuffix(suffix), openAttempts, openDelay);
    }

    private static String checkSuffix(String suffix) {
        String result = suffix.startsWith("/") ? suffix : "/" + suffix;
        if (result.length() > 1 && result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        if (result.contains("\\") || result.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Suffix (" + suffix + ") can't contain a backslash or a null character");
        }
        final String[] names = result.substring(1).split("/", -1);
        for (String name : names) {
            if (name.isBlank() || ".".equals(name) || "..".equals(name)) {
                throw new IllegalArgumentException("Suffix (" + suffix + ") should be a sub folder, without empty names, . or ..");
            }
        }
        return result;
    }

    /**
     * Returns the folder at the root of the folder location.
     * @param source true if the folder location is the source, false if it is the destination
     * @return the entry. If <code>source</code> is true, the entry's provider is read-only, otherwise, if the entry does not exist and path is not, it is created.
     * The caller has to close the provider of the returned folder.
     * @throws IOException if an I/O error occurs
     */
    public Folder entry(boolean source) throws IOException {
        if (suffix == null) {
            return open(path, false);
        }
        boolean hasTrailingSlash = path.endsWith("/");
        final String rootPath = hasTrailingSlash ? path : path + suffix;
        if (source) {
            Folder result = open(rootPath, false);
            result.getFileProvider().setReadOnly(true);
            return result;
        } else {
            boolean created = false;
            Entry entry = provider.apply(path).get(suffix);
            try {
                if (!entry.exists()) {
                    // Try to create the folder
                    createFolder(entry);
                    created = true;
                }
            } finally {
                entry.getFileProvider().close();
            }
            // If the folder was just created, it may not be visible yet
            return open(rootPath, created);
        }
    }

    /**
     * Opens the root folder of a path.
     * @param rootPath the path
     * @param retry true to try again, with an increasing delay, if the folder can't be opened (typically because it was just created and is not yet visible)
     */
    private Folder open(String rootPath, boolean retry) throws IOException {
        int attempt = 1;
        Duration delay = openDelay;
        while (true) {
            FileProvider fileProvider = null;
            try {
                fileProvider = provider.apply(rootPath);
                return fileProvider.get(FileProvider.ROOT_PATH).asFolder();
            } catch (IllegalArgumentException e) {
                // Some providers reject a root that is not a folder with an unchecked exception. It is not a transient error.
                throw new IOException("Can't open " + rootPath + ": " + e.getMessage(), e);
            } catch (IOException | IllegalStateException e) {
                if (fileProvider != null) {
                    fileProvider.close();
                }
                if (!retry || attempt >= openAttempts) {
                    throw e;
                }
            }
            try {
                Thread.sleep(delay.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Interrupted while waiting for " + rootPath);
            }
            delay = delay.multipliedBy(2);
            attempt++;
        }
    }

    private static void createFolder(Entry entry) throws IOException {
        Entry parent = entry.getParent();
        if (parent.isFile()) {
            throw new IOException("Parent of " + entry.getPath() + " is a file");
        }
        if (!parent.exists()) {
            createFolder(parent);
        }
        parent.asFolder().mkdir(entry.getName());
    }
}
