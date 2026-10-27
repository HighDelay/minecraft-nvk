package dev.nvkbridge.runtime;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Locale;
import java.util.Properties;

public final class RuntimeSelector {
    static final Path DEFAULT_RUNTIME = Path.of("/Library/GPUBundles/nvmtl/minecraft");

    public enum Status { SELECTED, UNCONFIGURED, DISABLED, UNSUPPORTED_PLATFORM, INVALID, CONFLICT }

    public record Selection(Status status, RuntimeManifest runtime, String message) {
        public boolean selected() { return status == Status.SELECTED; }
    }

    private RuntimeSelector() {}

    public static Selection select(Properties settings) {
        return select(settings, DEFAULT_RUNTIME);
    }

    // Supply the installed location separately so tests never depend on the host's driver installation.
    static Selection select(Properties settings, Path installedRuntime) {
        String enabled = settings.getProperty("nvkbridge.enabled", "true").trim();
        if (enabled.equalsIgnoreCase("false")) return result(Status.DISABLED, "Disabled by nvkbridge.enabled=false");
        if (!enabled.equalsIgnoreCase("true")) return result(Status.INVALID, "nvkbridge.enabled must be true or false");
        String os = settings.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = settings.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (!(os.equals("mac os x") || os.equals("macos") || os.equals("darwin"))
            || !(arch.equals("x86_64") || arch.equals("amd64"))) {
            return result(Status.UNSUPPORTED_PLATFORM, "Runtime selection applies to Intel macOS only");
        }
        String configured = settings.getProperty("nvkbridge.runtime", "").trim();
        try {
            Path directory = configured.isEmpty() ? installedRuntime : Path.of(configured);
            if (configured.isEmpty() && Files.notExists(directory)) {
                return result(Status.UNCONFIGURED, "No installed runtime at " + directory
                    + "; keeping Minecraft's normal Vulkan provider");
            }
            RuntimeManifest runtime = RuntimeManifest.load(directory);
            String existing = settings.getProperty("org.lwjgl.vulkan.libname", "").trim();
            if (!existing.isEmpty() && !sameLibrary(existing, runtime.library())) {
                return result(Status.CONFLICT, "Existing org.lwjgl.vulkan.libname override takes precedence; remove it to use NVK Bridge");
            }
            return new Selection(Status.SELECTED, runtime, "Selected runtime " + runtime.runtimeId());
        } catch (IOException | IllegalArgumentException exception) {
            return result(Status.INVALID, exception.getMessage());
        }
    }

    private static Selection result(Status status, String message) {
        return new Selection(status, null, message);
    }

    static boolean sameLibrary(String configured, Path selected) {
        try {
            return Files.isSameFile(Path.of(configured), selected);
        } catch (IOException | IllegalArgumentException exception) {
            return false;
        }
    }
}
