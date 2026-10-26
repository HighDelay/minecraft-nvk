# Sources and reproducibility

Inspected on 2026-10-26. The client inspection record is [evidence/minecraft-26.3-bootstrap.json](evidence/minecraft-26.3-bootstrap.json). The official client is downloaded only for local verification and is not redistributed.

## Native driver

- [Frontend source and build](https://github.com/HighDelay/nvidia-macos-driver/tree/main/nvk/native-macos): actual Metal surface/swapchain implementation and native tests.
- [Fork merge notes](https://github.com/HighDelay/nvidia-macos-driver/blob/main/docs/FORK-MERGE-NOTES.md): retained driver behavior and validation limits.
- [Driver integration map](DRIVER-CHANGE-MAP.md): runtime ownership and publication requirements.

The gameplay test used driver base `644ded0de335ea286d71d59ab6d987b1483f729b` with the new frontend built locally. Exact binary and source identities are recorded in [test results](TEST-RESULTS.md).

## Primary references

- [Mojang version manifest](https://piston-meta.mojang.com/mc/game/version_manifest_v2.json) and [26.3 metadata](https://piston-meta.mojang.com/v1/packages/61fff1316bd50312624a858bbb5eb18fa45208ed/26.3.json): Java 25 and LWJGL 3.4.3 dependencies, including SDL. Metadata SHA-1: `61fff1316bd50312624a858bbb5eb18fa45208ed`; client SHA-1: `e877b6a07acd633fb3bb475002175cec036e7b87`.
- [LWJGL 3.4.3 VK.java](https://github.com/LWJGL/lwjgl3/blob/3.4.3/modules/lwjgl/vulkan/src/main/java/org/lwjgl/vulkan/VK.java): provider selection and loading behavior.
- [SDL_Vulkan_LoadLibrary](https://wiki.libsdl.org/SDL3/SDL_Vulkan_LoadLibrary), [SDL_Vulkan_GetVkGetInstanceProcAddr](https://wiki.libsdl.org/SDL3/SDL_Vulkan_GetVkGetInstanceProcAddr) and [SDL_Vulkan_CreateSurface](https://wiki.libsdl.org/SDL3/SDL_Vulkan_CreateSurface): library selection, matching provider and surface creation.
- [VK_EXT_metal_surface](https://docs.vulkan.org/refpages/latest/refpages/source/VK_EXT_metal_surface.html): CAMetalLayer surfaces.
- [Initial native WSI investigation](https://github.com/nullmoth/nvidia-macos-driver/issues/325): reference experiment. The implemented frontend uses the installed renderer directly; it does not apply that experiment's Mesa patch or claim its later GPU bridge source was available.

Minecraft 26.3 uses SDL. The production mod adds no separate SDL or GLFW initialization. Offline structural checks alone do not prove runtime rendering; the native and gameplay checks are recorded separately.
