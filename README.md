# sync4j-config

[![Maven Central](https://img.shields.io/maven-central/v/com.fathzer/sync4j-config)](https://search.maven.org/artifact/com.fathzer/sync4j-config)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Javadocs](https://www.javadoc.io/badge/com.fathzer/sync4j-config.svg)](https://www.javadoc.io/doc/com.fathzer/sync4j-config)
[![SonarCloud](https://sonarcloud.io/api/project_badges/measure?project=sync4j_sync4j-config&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=sync4j_sync4j-config)

Reads the description of [sync4j](https://github.com/fathzer/sync4j) synchronizations from a JSON configuration: *which* folders to synchronize, on *which* providers, with *which* filters.

It is used by programs that are driven by a configuration file, like [sync4j-web](https://github.com/sync4j/sync4j-web), and can be used by others (a command line, for instance).

## Requirements

- Java 17

## The description of a synchronization

```json
{
  "libraries": [
    {
      "id": "photos",
      "label": "My photos",
      "source": { "provider": "pCloud", "path": "/Photos", "config": { "zone": "us", "token": "${PCLOUD_TOKEN}" } },
      "destination": { "provider": "local", "path": "${LOCAL_PATH_PREFIX}/photos" },
      "filters": ["- *.xmp"]
    }
  ],
  "filters": ["+ /important/**.db", "- *.tmp", "- *.db*"]
}
```

- A *library* is a source folder that is synchronized with a destination folder. `id` can contain alphanumeric characters, `-` and `_`, and is unique. `label` is optional (the id by default).
- `provider` is the type of the provider of a location (see below). `path` is the path of the root of the location, and `config` is the configuration of the provider (the content depends on the provider).
- `filters` are the rules that tell which entries are synchronized: `- pattern` excludes the entries that match the glob pattern, `+ pattern` includes them. The first rule that matches an entry decides, an entry that no rule matches is synchronized (so an exception goes before the general rule: `["+ /important/**.db", "- *.db*"]`). A pattern that starts with `/` is anchored to the root of the library, any other one can match at any level (`*.tmp` matches the `.tmp` files of all the folders). The syntax is described by `FilterRules` in sync4j. The rules of the root apply to all the libraries, the optional ones of a library are tried **before** them (so a library can override a global rule): `SynchronizationConfig.filters(library)` returns the rules to apply. An invalid rule, or the old `excludePatterns` attribute, is an error.
- `${VARIABLE}` is replaced by the value of the environment variable in `path` and in all the text values of `config`. It is an error if the variable is not defined.

```java
ProviderRegistry providers = ProviderRegistry.withDefaults();
SynchronizationConfig config = SynchronizationConfig.parse(jsonNode, providers); // jsonNode is the description above, read with Jackson
for (Library library : config.libraries()) {
    Folder source = library.source().entry(true);          // The provider of a source is read-only
    Folder destination = library.destination().entry(false); // The destination is created if it does not exist
    // ... use them with sync4j (the caller has to close the providers: source.getFileProvider().close())
}
```

`FolderLocation.withSuffix("sub/folder")` returns a location on a sub folder (the original location is not modified). If a destination folder was just created, opening it is tried again a few times, because some storages need a little time to show a new folder.

## Providers

The `provider` of a location is the type of a `FileProviderFactory` (an interface of sync4j that creates a provider from a configuration). The `ProviderRegistry` knows the factories that are:

- found on the classpath of the program: the ones of sync4j (`local`, the local file system, and `memory`, an empty in memory file system), and the ones of the provider libraries that ship their factory, like [sync4j-pcloud](https://github.com/sync4j/sync4j-pcloud) (`"provider": "pCloud"`),
- found in the jar files of a directory, `ProviderRegistry.withDefaults(directory)`, which allows to add a provider without rebuilding the program,
- or registered by hand: `registry.register(factory)`.

### The configuration of a provider

A factory declares the class of its configuration (`FileProviderFactory<T>`): a `record`, for instance `record PCloudConfig(Zone zone, String token)`. The `config` attribute of the location is converted to this class with Jackson (the factory does not depend on it, the class needs no annotation if it is a record), then given to the factory, that checks the values it needs.

- The environment variables are replaced in the texts before the conversion.
- A text is converted to the type of the property (`"42"` can be the value of an integer), so a variable can be used for any property.
- The names of the values of an enumeration are not case sensitive (`"us"` is `US`).
- An unknown property is an error, and the message tells where and what is wrong: `The configuration of source of library photos is not valid: unknown property 'tokn' (the known properties are token, zone)`.
- A provider without configuration (like `local`) has the type `Void`: `config` must be missing or empty.

### The path

`path` is the root of the location in the provider. It is not a part of the configuration of the provider because the same configuration (an account) can be used with many roots, and because the root can change at each synchronization (`FolderLocation.withSuffix` opens the provider on a sub folder). It is required, except for the providers that have no root path like `memory` (`FileProviderFactory.isRootPathRequired()`).

### Discovery

The factories are discovered with [plugin-loader](https://github.com/fathzer/plugin-loader), see `PluginDiscovery` (it can be used to discover other kinds of plugins). As for `java.util.ServiceLoader`, an implementation is declared in the `META-INF/services/<full name of the interface>` file of its jar and needs a public constructor without argument. An implementation that can't be loaded is logged and ignored: it does not prevent the other ones to be discovered.

If a program is packaged in a single jar (for instance with the Maven shade plugin), the `META-INF/services` files of its dependencies have to be merged (`ServicesResourceTransformer`).
