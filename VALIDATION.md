# NVK Bridge validation

Updated on Intel macOS Tahoe 26.7.1 on 2026-10-26 with Java 25 and Gradle 9.6.0. The original handoff also reported Windows build and wrapper integrity checks.

| Check | Result | Meaning |
|---|---|---|
| `sh gradlew build` | Pass | Both 0.1.1 loader variants compile and package. |
| Runtime-selection JUnit suite | 18 tests pass, zero failures/errors/skips | Selection, disabling, platform, manifests, hashes, Mach-O slices, overrides and same-file path aliases. Synthetic test dylibs are not native-loaded. |
| `python tools/verify_release.py --download-client` | Pass | Both jars contain their own loader metadata, shared code and client Mixin, have Java 25 bytecode, and do not bundle loader/Mixin/LWJGL/game classes. Exact official client identity is checked. |
| `:common:verifyMinecraftHooks` | Pass | Exact bootstrap method/call, compiled injector annotations, vanilla SDL library path and provider equality logic; 11,383 client classes checked for GLFW class references, none found. No game code executes. |
| `:common:verifyMixinTransformation`, Mixin `0.17.4+mixin.0.8.7` | Pass | NeoForge's Mixin engine accepts `JAVA_25` and applies the production config to the exact client class. Both injected calls remain inside vanilla's original `Throwable` handler; changed methods pass ASM stack analysis. |
| Same transformation task, `-PmixinTestVersion=0.17.5+mixin.0.8.7` | Pass | This alternate engine passes the same transformation checks. The test service supplies class bytes and refuses client/LWJGL class definitions; Minecraft never executes. |
| Runtime manifest writer smoke check | Pass | Computes the real entry hash, writes a relative library path and refuses accidental manifest replacement. |
| Gradle wrapper integrity | Pass | Wrapper jar matched Gradle's published SHA-256; distribution SHA-256 is pinned. |
| `:common:verifyNativeRuntime` against the final runtime | Pass | Actual mod selection, dylib load, provider path, SDL provider pointer equality, NVIDIA enumeration and Metal surface creation. |
| Full Fabric Essential launch | Pass | Native NVK selected; menu and multiplayer rendering worked, confirmed by the user. |
| NeoForge 26.3.0.99 game session | Pass | User confirmed correct operation in Minecraft 26.3; the session log identifies Vulkan 1.4.363 NVK Mesa 26.3.0-devel. |
| OpenGL startup notification | Structural and real Mixin checks pass | The actual selected-backend and toast APIs match the pinned client. Both Mixin versions apply the startup hook with valid stacks; visible toast behavior still needs an in-game check. |

The target client SHA-1 is `e877b6a07acd633fb3bb475002175cec036e7b87`; SHA-256 is `4508d006323f24fa02876310c192d739af56516eb259000ac50f0909a68c9a2d`. Its official metadata and the native integration requirements are linked in [the handoff sources](docs/SOURCES.md).

The code compiles against Fabric Loader 0.19.5 to preserve Essential's existing loader, and NeoForge FML 12.0.12 for the 26.3 line. Both loader variants have been launched in game on this desktop. Release jar hashes are in `dist/SHA256SUMS`.

## Native integration and limits

The local runtime supplies Metal surfaces and swapchains around a private copy of the installed native NVK renderer. Real device requirements and vanilla Vulkan availability checks remain enabled. Native presentation, resize/restore and pixel validation pass on the 2060 SUPER. The mod also handles equivalent `/var` and `/private/var` or symlink spellings of an existing Vulkan override.

See [TEST-RESULTS.md](docs/TEST-RESULTS.md) for exact identities, hardware evidence and limits. A controlled FPS comparison, Sequoia gameplay, other GPUs, long sessions and general Vulkan WSI conformance remain untested. There is no zero-copy or broad modpack compatibility claim.
