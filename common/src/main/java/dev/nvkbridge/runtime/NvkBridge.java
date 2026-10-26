package dev.nvkbridge.runtime;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.lwjgl.system.Configuration;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.SharedLibrary;
import org.lwjgl.vulkan.VK;
import org.lwjgl.vulkan.VkExtensionProperties;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.VK_API_VERSION_1_2;

public final class NvkBridge {
    private static final System.Logger LOG = System.getLogger("nvkbridge");
    private static RuntimeSelector.Selection selection;
    private static boolean verified;

    private NvkBridge() {}

    public static synchronized void configureBeforeVulkanCreate() {
        if (selection != null) return;
        selection = RuntimeSelector.select(System.getProperties());
        if (!selection.selected()) {
            LOG.log(selection.status() == RuntimeSelector.Status.INVALID || selection.status() == RuntimeSelector.Status.CONFLICT
                ? System.Logger.Level.WARNING : System.Logger.Level.INFO,
                "NVK Bridge: {0}: {1}", selection.status(), selection.message());
            return;
        }
        String current = Configuration.VULKAN_LIBRARY_NAME.get();
        String desired = selection.runtime().library().toString();
        if (current != null && !RuntimeSelector.sameLibrary(current, selection.runtime().library())) {
            selection = new RuntimeSelector.Selection(RuntimeSelector.Status.CONFLICT, null,
                "Another component already configured LWJGL's Vulkan library; preserving its selection");
            LOG.log(System.Logger.Level.WARNING, "NVK Bridge: {0}", selection.message());
            return;
        }
        Configuration.VULKAN_LIBRARY_NAME.set(desired);
        LOG.log(System.Logger.Level.INFO,
            "NVK Bridge: selected {0}; library={1}; declared presentation={2}; sha256={3}",
            selection.runtime().runtimeId(), desired, selection.runtime().presentation(), selection.runtime().sha256());
    }

    public static synchronized void verifyLoadedProvider() {
        if (selection == null || !selection.selected() || verified) return;
        try {
            if (!(VK.getFunctionProvider() instanceof SharedLibrary library) || library.getPath() == null
                || !Files.isSameFile(Path.of(library.getPath()), selection.runtime().library())) {
                throw new IllegalStateException("LWJGL loaded a different Vulkan library than the selected runtime");
            }
            if (VK.getInstanceVersionSupported() < VK_API_VERSION_1_2) {
                throw new IllegalStateException("Selected runtime does not expose Vulkan 1.2");
            }
            Set<String> extensions = instanceExtensions();
            if (!extensions.contains("VK_KHR_surface") || !extensions.contains("VK_EXT_metal_surface")) {
                throw new IllegalStateException("Selected runtime lacks VK_KHR_surface or VK_EXT_metal_surface; use a window-capable build");
            }
            verified = true;
            LOG.log(System.Logger.Level.INFO,
                "NVK Bridge: provider path and Metal surface extensions verified; vanilla retains device checks and SDL provider matching");
        } catch (IOException | RuntimeException exception) {
            LOG.log(System.Logger.Level.ERROR, "NVK Bridge: runtime validation failed; Minecraft will handle Vulkan availability", exception);
            throw new IllegalStateException("NVK Bridge runtime validation failed", exception);
        }
    }

    private static Set<String> instanceExtensions() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer count = stack.callocInt(1);
            int result = vkEnumerateInstanceExtensionProperties((ByteBuffer) null, count, null);
            if (result != VK_SUCCESS || count.get(0) < 1 || count.get(0) > 4096) {
                throw new IllegalStateException("Unable to enumerate selected runtime's instance extensions: " + result);
            }
            VkExtensionProperties.Buffer properties = VkExtensionProperties.calloc(count.get(0), stack);
            result = vkEnumerateInstanceExtensionProperties((ByteBuffer) null, count, properties);
            if (result != VK_SUCCESS) throw new IllegalStateException("Instance extension enumeration failed: " + result);
            Set<String> extensions = new HashSet<>();
            for (int i = 0; i < count.get(0); i++) extensions.add(properties.get(i).extensionNameString());
            return extensions;
        }
    }

    public static synchronized void logStatus() {
        LOG.log(System.Logger.Level.INFO, "NVK Bridge: {0}", selection == null
            ? "Vulkan bootstrap has not run; restart after configuring the runtime"
            : selection.selected() ? verified ? "Runtime provider verified" : "Runtime selected; native Vulkan initialization was not verified"
                : selection.status() + ": " + selection.message());
    }
}
