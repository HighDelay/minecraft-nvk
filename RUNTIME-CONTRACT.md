# NVK Bridge runtime contract, version 1

The companion driver build produces an isolated runtime directory. The Fabric and NeoForge mods select its application-facing Vulkan library before Minecraft 26.3 calls `VK.create()`.

## Directory and manifest

```text
nvk-runtime/
  nvk-bridge-runtime.properties
  lib/
    libnvk_minecraft.dylib
    libnvk_renderer.dylib
```

`nvk-bridge-runtime.properties` is a UTF-8 Java properties file with these required keys:

| Key | Value |
|---|---|
| `schema.version` | `1` |
| `target.os` | `macos` |
| `target.arch` | `x86_64` |
| `minecraft.version` | `26.3` |
| `runtime.id` | A nonempty identifier for the actual native build |
| `library.path` | A relative path to the Vulkan entry dylib, `lib/libnvk_minecraft.dylib` in the current runtime |
| `library.sha256` | The actual 64-digit SHA-256 of that entry dylib |
| `presentation` | `gpu-copy` or `cpu-staging` |

Use `tools/write_runtime_manifest.py` to hash an existing native library and write this file. `presentation` records the producer's declaration; the mod cannot prove the native frame-copy implementation from this string. `cpu-staging` is allowed for bring-up and is not a performance claim. Direct zero-copy is outside version 1.

The library must be a readable x86_64 Mach-O dylib, or a universal Mach-O containing an x86_64 dylib slice. The resolved library must remain within the runtime directory. The mod hashes the entry library; it does not certify all dependencies or a native build's behavior.

## Application-facing Vulkan interface

The entry library must work directly with both LWJGL and SDL, without depending on a per-user or system ICD configuration. It must export `vkGetInstanceProcAddr` and return the normal application global commands for a null instance:

- `vkCreateInstance`
- `vkEnumerateInstanceExtensionProperties`
- `vkEnumerateInstanceLayerProperties` (a valid zero-layer result is acceptable)
- `vkEnumerateInstanceVersion` for the advertised API version

LWJGL resolves global commands through `vkGetInstanceProcAddr`; SDL independently loads the same real library path and uses its instance commands. An ICD exposing only `vk_icdGetInstanceProcAddr` needs an application-facing export/facade. The installed plugin's direct ICD-loading ability does not establish all these application requirements.

Expose real `VK_KHR_surface`, `VK_EXT_metal_surface` and `VK_KHR_swapchain` support. The mod checks the loaded path, instance API >=1.2, and both instance surface extensions. Minecraft retains its own device features, queue suitability, swapchain and SDL provider-equality checks. Do not spoof supported features or force portability flags; 26.3 already handles portability enumeration conditionally.

All application rendering objects belong to the selected runtime's own Vulkan device. The installed Metal plugin may load its own NVK in the same process for drawables. Resolve private symbols to the intended library/device, use suitable local symbol scope and distinct install names where necessary, and test that dyld does not coalesce the isolated and installed implementations unexpectedly. Share backing memory through the existing private protocol, never cast another device's `VkImage` or `VkDeviceMemory`.

The recommended first optimized presentation path performs one GPU copy into imported drawable VRAM. Keep the producer fence, surface publication, drawable retention, per-image reservations and teardown ordering described in [the driver map](docs/DRIVER-CHANGE-MAP.md). Package native dependencies with working `@loader_path`/`@rpath` references. Validate actual loading on Intel macOS.

## Mod configuration and startup

Starting with mod **0.1.2**, Intel macOS instances automatically check `/Library/GPUBundles/nvmtl/minecraft`. Installing the driver package and matching bridge jar is sufficient; select Minecraft's Vulkan backend and restart. No JVM runtime property is needed for that installed location.

For a custom runtime directory, set this optional JVM property:

```text
-Dnvkbridge.runtime=/absolute/path/to/nvk-runtime
```

An absent or blank runtime property uses automatic detection. If the default directory is missing, selection stays unconfigured and Minecraft keeps its normal provider. An explicit custom path takes priority; an invalid custom runtime is reported without substituting the installed runtime. `-Dnvkbridge.enabled=false` disables selection, including automatic detection. Unsupported operating systems and Apple Silicon keep normal startup. Invalid manifests are rejected before a native override is applied, with a log message. A competing explicit `org.lwjgl.vulkan.libname` setting is preserved; remove it when selecting this runtime through the mod.

The shared Mixin targets `com.mojang.blaze3d.platform.NativeLibrariesBootstrap.tryLoadingVulkan()Z`, immediately before and after `org.lwjgl.vulkan.VK.create()V`. It sets `Configuration.VULKAN_LIBRARY_NAME` before loading and verifies the provider afterwards. No separate SDL or GLFW initialization is added.

An explicit LWJGL library selection does not retry bundled MoltenVK on native-load failure. A load/verification failure follows Minecraft's Vulkan-unavailable behavior; the mod does not destroy/recreate global Vulkan state or promise same-process Vulkan failover. Leave Minecraft's renderer preference and fallback logic intact.

## Integration acceptance

For both loader variants, establish the native library path from process evidence, matching SDL/LWJGL providers, a selected NVIDIA device, correct presentation and resize/recreation behavior. Validate gameplay and performance on physical hardware. Compilation, manifest checks and bytecode hook checks alone do not establish those outcomes.

Baseline client identity and feature requirements are retained in [docs/evidence/minecraft-26.3-bootstrap.json](docs/evidence/minecraft-26.3-bootstrap.json).
