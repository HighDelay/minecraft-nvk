package dev.nvkbridge.runtime;

public final class OpenGlWarning {
    public static final String MESSAGE = "It seems that you are on OpenGL. Please switch to Vulkan "
        + "for this mod to work with HighDelay's nvidia-macos-driver.";
    private static final System.Logger LOG = System.getLogger("nvkbridge");
    private static final OpenGlWarning INSTANCE = new OpenGlWarning();
    private boolean checked;

    public static void onGameLoadFinished(Object minecraft) {
        if (INSTANCE.checked) return;
        try {
            Class<?> renderSystem = Class.forName("com.mojang.blaze3d.systems.RenderSystem");
            Object device = renderSystem.getMethod("getDevice").invoke(null);
            Class<?> gpuDevice = Class.forName("com.mojang.renderpearl.api.device.GpuDevice");
            Object info = gpuDevice.getMethod("getDeviceInfo").invoke(device);
            Class<?> deviceInfo = Class.forName("com.mojang.renderpearl.api.device.DeviceInfo");
            String backend = (String) deviceInfo.getMethod("backendName").invoke(info);
            if (!INSTANCE.shouldWarn(backend)) return;
            LOG.log(System.Logger.Level.WARNING, "NVK Bridge: {0}", MESSAGE);
            showToast(minecraft);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            INSTANCE.checked = true;
            LOG.log(System.Logger.Level.WARNING, "NVK Bridge: could not show the graphics backend notification", exception);
        }
    }

    private boolean shouldWarn(String backend) {
        if (checked) return false;
        checked = true;
        return "OpenGL".equalsIgnoreCase(backend);
    }

    private static void showToast(Object minecraft) throws ReflectiveOperationException {
        Class<?> client = Class.forName("net.minecraft.client.Minecraft");
        Object gui = client.getField("gui").get(minecraft);
        Class<?> guiType = Class.forName("net.minecraft.client.gui.Gui");
        Object manager = guiType.getMethod("toastManager").invoke(gui);
        Class<?> component = Class.forName("net.minecraft.network.chat.Component");
        Object title = component.getMethod("literal", String.class).invoke(null, "NVK Bridge: OpenGL selected");
        Object message = component.getMethod("literal", String.class).invoke(null, MESSAGE);
        Class<?> toastId = Class.forName("net.minecraft.client.gui.components.toasts.SystemToast$SystemToastId");
        Object id = toastId.getConstructor(long.class).newInstance(12000L);
        Class<?> toast = Class.forName("net.minecraft.client.gui.components.toasts.SystemToast");
        Class<?> managerType = Class.forName("net.minecraft.client.gui.components.toasts.ToastManager");
        toast.getMethod("add", managerType, toastId, component, component)
            .invoke(null, manager, id, title, message);
    }
}
