# Minecraft NVK Bridge

Run Minecraft 26.3's Vulkan renderer through native NVK on Intel macOS with a compatible NVIDIA driver. This client mod selects the external Vulkan runtime before Minecraft initializes LWJGL; it keeps Minecraft's renderer and feature checks.

The companion [driver frontend](https://github.com/HighDelay/nvidia-macos-driver/tree/main/nvk/native-macos) supplies Metal surfaces and swapchains. Rendering goes directly through NVK, bypassing MoltenVK and Metal shader translation. Finished frames reach the Metal drawable through a GPU copy. The mod itself performs no rendering or pixel transfer.

## Requirements

- Minecraft **26.3**, Java **25**.
- Fabric Loader **0.19.5+**, or NeoForge **26.3.0.99+** in the 26.3 line.
- Intel macOS, a compatible NVIDIA driver and a matching native NVK runtime with window presentation. The driver's ordinary headless NVK library alone is insufficient.

Fabric Essential menu and multiplayer rendering were tested on RTX 2060 SUPER / Tahoe 26.7.1. NeoForge builds and offline transformation checks pass; a NeoForge game launch remains untested. See [test results](docs/TEST-RESULTS.md).

## Build

```sh
./gradlew build
```

Use `gradlew.bat build` on Windows. The wrapper downloads pinned Gradle 9.6.0. Install JDK 25 first.

The installable jars and `SHA256SUMS` are written to `dist/`:

```text
nvk-bridge-fabric-26.3-0.1.1.jar
nvk-bridge-neoforge-26.3-0.1.1.jar
```

Only the mod's own code, metadata and license are bundled. Fabric API is not required; game, loader, LWJGL and Mixin classes are supplied by the installation.

## Install

1. Put the jar matching your loader in the instance's `mods` directory.
2. Build the [native runtime](https://github.com/HighDelay/nvidia-macos-driver/tree/main/nvk/native-macos) against your compatible driver. Keep its manifest and both libraries together in a persistent directory.
3. Add this JVM argument to that instance, using the runtime directory's absolute path:

   ```text
   -Dnvkbridge.runtime=/absolute/path/to/nvk-runtime
   ```

4. Select Minecraft's Vulkan graphics backend and restart the game.
5. Check `NVK Bridge` startup messages and the F3 renderer string. The tested native renderer identifies itself as `NVK Mesa`, rather than MoltenVK.

The mod verifies the manifest, entry-library SHA-256 and Mach-O architecture before selection. After loading it checks the actual provider path, Vulkan instance version and Metal surface extensions. Minecraft still checks device features and confirms SDL uses the same Vulkan provider.

An OpenGL launch shows a one-time startup toast and logs: “It seems that you are on OpenGL. Please switch to Vulkan for this mod to work with HighDelay's nvidia-macos-driver.” It checks the actual selected backend after startup, without changing your graphics settings. Vulkan launches do not show this warning.

An explicit competing `-Dorg.lwjgl.vulkan.libname=...` override takes precedence. Remove it to use this selector. When paths name the same file, symlink and `/var` versus `/private/var` aliases are accepted.

## Disable or compare

Add `-Dnvkbridge.enabled=false` and restart Minecraft to use its normal provider. Removing the bridge jar, or removing the runtime property, also leaves ordinary provider selection intact. Unsupported platforms receive no override.

Explicit native loading does not automatically retry MoltenVK after a failure. Minecraft handles Vulkan unavailability; the mod does not unload or replace Vulkan mid-process.

Compare the same world, location, camera, window size, VSync and settings after caches warm. The functional screenshot showed about 148 FPS in one scene; it is not a controlled benchmark. No performance gain or removal of the refresh-related frame-rate ceiling is established yet.

## Verify

```sh
./gradlew build
python3 tools/verify_release.py --download-client
./gradlew :common:verifyMinecraftHooks :common:verifyMixinTransformation
./gradlew :common:verifyMixinTransformation -PmixinTestVersion=0.17.5+mixin.0.8.7
```

The Python verifier requires Python 3.11+. The exact official client is downloaded only into ignored `.cache/`, checked against its pinned hash and inspected as data; it is not redistributed.

On Intel macOS, test actual mod/LWJGL/SDL loading with a built runtime:

```sh
./gradlew :common:verifyNativeRuntime -PnvkRuntime=/absolute/path/to/nvk-runtime
```

This creates a hidden window and a real Metal surface, not a Minecraft session or benchmark. See [validation](VALIDATION.md), [runtime contract](RUNTIME-CONTRACT.md) and [sources](docs/SOURCES.md).

## License

The mod is [MIT licensed](LICENSE). The Gradle wrapper retains Apache-2.0; see [third-party notices](THIRD-PARTY.md). The separate native runtime and NVIDIA driver retain their existing licenses and are not bundled in these mod jars.
