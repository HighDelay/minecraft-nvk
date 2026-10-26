# Build components and runtime dependencies

The NVK Bridge mod and its original code use the [MIT license](LICENSE), included in each output jar. The separate NVIDIA driver and native runtime retain their own licenses; this repository does not relicense or bundle them.

The standard Gradle wrapper files retain their original notices; the full [Apache license](licenses/Apache-2.0.txt) is included. They come from [Gradle v9.6.0](https://github.com/gradle/gradle/tree/v9.6.0) under [Apache License 2.0](https://github.com/gradle/gradle/blob/v9.6.0/LICENSE). The downloaded wrapper jar SHA-256 was checked against Gradle's published wrapper checksum:

```text
497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7
```

The Gradle distribution SHA-256 is pinned in `gradle/wrapper/gradle-wrapper.properties`.

Fabric Loader, NeoForge FML, Sponge Mixin and LWJGL are supplied by the game/mod-loader installation. Their classes and native binaries are not included in the mod jars. JUnit, ASM and Mixin are used for verification. The optional native verification task also uses LWJGL's macOS and SDL natives as test dependencies, outside the mod jars. The official client downloaded for hook verification stays in ignored `.cache/` and is not redistributed.
