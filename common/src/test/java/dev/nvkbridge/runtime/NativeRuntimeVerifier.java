package dev.nvkbridge.runtime;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.Configuration;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.SharedLibrary;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VK;

import static org.lwjgl.sdl.SDLInit.*;
import static org.lwjgl.sdl.SDLVideo.*;
import static org.lwjgl.sdl.SDLVulkan.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.VK_API_VERSION_1_2;

public final class NativeRuntimeVerifier {
    private static void require(boolean result, String label) {
        if (!result) throw new IllegalStateException(label);
    }

    public static void main(String[] args) throws Exception {
        require(args.length <= 1, "Use the installed runtime or pass -PnvkRuntime=/absolute/runtime/root");
        boolean custom = args.length == 1 && !args[0].isBlank();
        if (custom) System.setProperty("nvkbridge.runtime", args[0]);
        else System.clearProperty("nvkbridge.runtime");
        var selection = RuntimeSelector.select(System.getProperties());
        require(selection.selected(), "Runtime selection: " + selection.status() + ": " + selection.message());
        RuntimeManifest expected = selection.runtime();
        Configuration.VULKAN_EXPLICIT_INIT.set(true);
        NvkBridge.configureBeforeVulkanCreate();
        VK.create();
        NvkBridge.verifyLoadedProvider();
        SharedLibrary provider = (SharedLibrary) VK.getFunctionProvider();
        require(Files.isSameFile(Path.of(provider.getPath()), expected.library()), "Wrong native provider");
        require(SDL_Init(SDL_INIT_VIDEO), "SDL video initialization");
        require(SDL_Vulkan_LoadLibrary(provider.getPath()), "SDL Vulkan loading");
        require(SDL_Vulkan_GetVkGetInstanceProcAddr() == provider.getFunctionAddress("vkGetInstanceProcAddr"),
            "SDL and LWJGL providers differ");
        long window = SDL_CreateWindow("NVK Bridge Java provider check", 320, 180, SDL_WINDOW_VULKAN | SDL_WINDOW_HIDDEN);
        require(window != 0, "SDL window creation");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkApplicationInfo app = VkApplicationInfo.calloc(stack).sType$Default().apiVersion(VK_API_VERSION_1_2)
                .pApplicationName(stack.UTF8("NVK Bridge Java probe"));
            VkInstanceCreateInfo info = VkInstanceCreateInfo.calloc(stack).sType$Default()
                .pApplicationInfo(app).ppEnabledExtensionNames(SDL_Vulkan_GetInstanceExtensions());
            PointerBuffer handle = stack.mallocPointer(1);
            require(vkCreateInstance(info, null, handle) == VK_SUCCESS, "Native instance creation");
            VkInstance instance = new VkInstance(handle.get(0), info);
            IntBuffer count = stack.callocInt(1);
            require(vkEnumeratePhysicalDevices(instance, count, null) == VK_SUCCESS && count.get(0) > 0, "Native adapters");
            PointerBuffer adapters = stack.mallocPointer(count.get(0));
            require(vkEnumeratePhysicalDevices(instance, count, adapters) == VK_SUCCESS, "Native adapter list");
            VkPhysicalDevice gpu = new VkPhysicalDevice(adapters.get(0), instance);
            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(stack);
            vkGetPhysicalDeviceProperties(gpu, props);
            require(props.vendorID() == 0x10de, "Native NVIDIA adapter");
            LongBuffer surface = stack.mallocLong(1);
            require(SDL_Vulkan_CreateSurface(window, instance, null, surface), "Native SDL surface");
            SDL_Vulkan_DestroySurface(instance, surface.get(0), null);
            vkDestroyInstance(instance, null);
            System.out.println("PASS actual LWJGL + mod runtime selection + SDL provider equality + NVIDIA Metal surface: "
                + props.deviceNameString() + "; provider=" + provider.getPath()
                + "; selection=" + (custom ? "explicit runtime" : "automatic installed runtime"));
        } finally {
            SDL_DestroyWindow(window);
            SDL_Vulkan_UnloadLibrary();
            SDL_Quit();
            VK.destroy();
        }
    }
}
