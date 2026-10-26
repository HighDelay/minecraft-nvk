# Native runtime integration

The implemented runtime lives in [HighDelay/nvidia-macos-driver, nvk/native-macos](https://github.com/HighDelay/nvidia-macos-driver/tree/main/nvk/native-macos). It wraps a private copy of the existing native NVK renderer rather than rebuilding Mesa or changing the installed driver.

## Ownership and presentation

- Export application-facing `vkGetInstanceProcAddr` and device dispatch. Forward native rendering commands to the intended NVK library using local symbol scope and a distinct renderer install name.
- Implement real Metal surfaces and swapchains. Keep ordinary Vulkan images and their bindings stable; Metal drawable slots have an independent lifetime/count.
- Obtain drawable backing identity, format, dimensions, plane, pitch and allocation size. Import its IOSurface memory on the application's own Vulkan device using protocol version 3.
- Wait for the game's render semaphores, record image-to-buffer copy commands, and wait for the producer fence. The tested renderer has a graphics/compute/transfer family, not a dedicated transfer-only family.
- Mark completed VRAM dirty and update its generation tag before presenting. Retain the drawable and imports through completion. Quarantine resources after uncertain completion rather than freeing them prematurely.
- Keep normal presentation free of CPU pixel readbacks/uploads. Diagnostic readbacks used by tests are separate.

The installed Metal plugin may initialize its own NVK instance in the same process. Do not borrow or cast its Vulkan objects into the application device. The two devices share backing memory through the private protocol, not object handles.

## Mod boundary

The shared Mixin configures LWJGL immediately before `VK.create()` in `NativeLibrariesBootstrap.tryLoadingVulkan()`. After loading it verifies the selected provider. Vanilla later passes that provider's path to SDL and checks pointer equality. The mod neither replaces rendering APIs nor initializes SDL separately.

Use the [runtime contract](../RUNTIME-CONTRACT.md) and [manifest writer](../tools/write_runtime_manifest.py). The native package owns dependency relocation, driver ABI compatibility and actual presentation. An extension name or manifest string does not establish those properties.

See [test results](TEST-RESULTS.md) for the tested hardware and limits. Multiple GPUs/windows, NeoForge gameplay, broad WSI conformance and a controlled FPS comparison remain unqualified.
