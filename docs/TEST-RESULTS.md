# Test results — 2026-10-26–27

## Hardware integration

Tested on Intel macOS Tahoe 26.7.1 with an RTX 2060 SUPER, 8 GiB BAR1 and fork driver 2.5.5. The Prism **26.3 Fabric Essential** instance retained Fabric Loader 0.19.5 and its existing mods.

Minecraft selected `Vulkan 1.4.363 NVK Mesa 26.3.0-devel` on the real NVIDIA device. The user confirmed correct menu and multiplayer rendering. The screenshot showed about **148 FPS** at a **1920×941** game window. This is functional evidence, not a matched performance comparison.

The running game mapped the local frontend and private renderer. A short process profile contained `queue_present` → `copy_frame` → native NVK completion calls. No MoltenVK mapping was found during that native launch.

## NeoForge integration

A separate Minecraft **26.3** instance with **NeoForge 26.3.0.99**, LWJGL **3.4.3**, Java **25** and the NeoForge 0.1.1 bridge was tested on the same desktop. The user confirmed it works. Its session log records `Using graphics backend Vulkan, using drivers: 1.4.363 NVK Mesa 26.3.0-devel`, world-rendering buffer activity and an ordinary `Stopping!` on exit. This confirms a native NVK game session; no NeoForge FPS comparison or long-session stability claim is made.

An earlier attempt used a Minecraft **1.21.1** / NeoForge **21.1.170** / Java **21** instance. Its loader rejected the jar before bridge initialization because it requires FML 12 and Minecraft 26.3. That instance uses an older OpenGL renderer and is outside the mod's supported versions.

## Checks

| Check | Result |
|---|---|
| Gradle build | Fabric and NeoForge jars compile/package. |
| Runtime selection unit tests | 26 pass on 0.1.2, zero failures/errors/skips. |
| Release verifier | Shared code, Java 25 bytecode, metadata and packaged license checked; game/loader/LWJGL classes excluded. |
| Exact client hooks | Target, call position, Throwable handler and vanilla SDL provider check verified. |
| Real Mixin transformation | Pass with Mixin 0.17.4 and 0.17.5; transformed methods pass ASM stack analysis. |
| Actual mod + LWJGL + SDL | Runtime manifest, loaded path, NVIDIA device, Metal surface and SDL/LWJGL provider pointer equality pass. |
| Native required features | All required Minecraft extensions/features pass the hardware probe. |
| Native three-image swapchain | 120 frames, resize and minimize/restore pass; all pixels checked by a separate 156,672,000-byte diagnostic readback. |
| Final runtime/four images, three drawable slots | 24 frames, resize/restore and all pixels pass; 31,334,400 diagnostic bytes checked; zero import failures. |
| Native frontend host tests | ASan/UBSan pass with mocked submissions: bounds, acquisition exhaustion/timeouts, failure rollback, handoff draining and API exception containment. |

Normal presentation contains no CPU pixel readback/upload. The test-only pixel observer deliberately performs a separate readback; it is disabled during gameplay. GPU-copy does not mean zero-copy or necessarily dedicated copy-engine execution.

## Tested identities

- Driver base: `644ded0de335ea286d71d59ab6d987b1483f729b`, with the native frontend now committed in [d9dd120](https://github.com/HighDelay/nvidia-macos-driver/commit/d9dd120).
- Frontend source SHA-256: `f7b897e11bb10e499155ff98de87dcce5dc5c829aa615c97a9871e49f24c56f3`.
- Native entry SHA-256: `7195ac76ac271a02beba6c59f31e37497b3956b88ed90fb0342b3e51769aa077`.
- Private renderer SHA-256: `9f2ae591f13933ccbe83f932de42c22285508e8b55375d2c5e7a05e011ced57f`.
- Original installed NVK renderer SHA-256: `a3a6451e7a193cbe2e71b5a6d769d14f8fd75b8728412bc28af944646d0af914`.

The installed driver was not replaced for this test. The runtime was isolated; existing driver defaults and other apps kept their ordinary providers. Source lives in the [companion driver repository](https://github.com/HighDelay/nvidia-macos-driver/tree/main/nvk/native-macos). This repository distributes the selector source, not those native binaries.

The initial gameplay jar was built before the standalone MIT packaging change. Publication checks compare its class payload with the MIT build; current hashes are generated in `dist/SHA256SUMS` and build outputs are excluded from Git.

Both MIT artifacts preserve all eight class files of their respective tested builds and add two classes for the OpenGL startup notification. Their license and loader metadata are updated consistently:

| Artifact | SHA-256 |
|---|---|
| Fabric 0.1.1 | `a883fb5d19fbe54e2a2cf85215ea1f09fcbbe7881566922ac17363cc9bb26362` |
| NeoForge 0.1.1 | `809d803abf8bc1f30668e49b0a7619292e34d0d02ec18d88b6da2ccaf5d0a30b` |

The new OpenGL toast has structural API and real Mixin transformation checks against the exact client, with stack analysis on both Mixin versions. Its visible in-game appearance has not been tested yet. It runs after startup and only checks the selected backend; rendering and Vulkan bootstrap classes are unchanged.

## Automatic runtime detection — 0.1.2

On 2026-10-27 the selector was updated to use `/Library/GPUBundles/nvmtl/minecraft` automatically on Intel macOS when no custom runtime property is supplied. The existing manifest, hash, architecture and provider checks remain active. Custom paths take priority, `nvkbridge.enabled=false` still disables the bridge, explicit competing LWJGL selections remain unchanged and a missing default directory keeps normal provider selection.

Both loader jars build and pass the release verifier. All 26 selector tests pass, including automatic selection, invalid defaults, custom-path priority and preserving explicit overrides. The exact-client hook check and real Mixin 0.17.4 transformation pass.

Running `:common:verifyNativeRuntime` without a runtime argument loaded `/Library/GPUBundles/nvmtl/minecraft/lib/libnvk_minecraft.dylib`, verified the NVIDIA device and SDL/LWJGL provider equality, and created a Metal surface on the RTX 2060 SUPER. The entry and private renderer hashes remain unchanged from the native integration above. A fresh 0.1.2 full-game session remains pending; the Fabric and NeoForge gameplay reports above are for 0.1.1.

| Artifact | SHA-256 |
|---|---|
| Fabric 0.1.2 | `d6849faf8010ca3152d2d8c1756611cb3ca92970f83b514182da3d61007ff4cf` |
| NeoForge 0.1.2 | `a32df78bb9e2ac57be8fa65bf9215cf1599fcb1aa1a20743bca71a49072af8b8` |

## Limits

- No controlled FPS comparison or removal of the previous refresh-related ceiling is established.
- The current frontend waits for the GPU copy and allocates/imports presentation resources each frame. Resource reuse is a possible follow-up after profiling.
- The tested renderer exposes one graphics/compute/transfer family, not a transfer-only family.
- Only this GPU with Fabric Essential and NeoForge 26.3.0.99 has game-session coverage. Sequoia gameplay, other GPUs/drivers, mixed GPUs, multiple windows, device loss recovery and long sessions remain unqualified.
- This is the implemented Minecraft presentation path, not a general Vulkan WSI conformance claim. Rendering mods making direct OpenGL calls still need compatible Vulkan implementations.

See [validation](../VALIDATION.md), [runtime contract](../RUNTIME-CONTRACT.md) and [sources](SOURCES.md).
