package dev.nvkbridge.runtime;

import java.io.IOException;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;
import static dev.nvkbridge.runtime.RuntimeSelector.Status.*;

class RuntimeSelectorTest {
    @TempDir Path temporary;

    @Test
    void selectsVerifiedRuntimeInDirectoryWithSpaces() throws Exception {
        Path root = makeRuntime("runtime with spaces", thinDylib(0x01000007));
        var selection = RuntimeSelector.select(settings(root));
        assertEquals(SELECTED, selection.status());
        assertEquals(root.toRealPath().resolve("lib/libvulkan_nouveau.dylib"), selection.runtime().library());
        assertEquals("gpu-copy", selection.runtime().presentation());
    }

    @Test
    void disabledSettingTakesPrecedenceOverRuntimeValidation() {
        Properties settings = settings(temporary.resolve("absent"));
        settings.setProperty("nvkbridge.enabled", "false");
        assertEquals(DISABLED, RuntimeSelector.select(settings).status());
    }

    @Test
    void invalidEnableValueDoesNotSelectLibrary() {
        Properties settings = settings(temporary);
        settings.setProperty("nvkbridge.enabled", "perhaps");
        assertEquals(INVALID, RuntimeSelector.select(settings).status());
    }

    @Test
    void windowsAndAppleSiliconKeepTheirNormalProvider() {
        Properties settings = settings(temporary.resolve("absent"));
        settings.setProperty("os.name", "Windows 11");
        assertEquals(UNSUPPORTED_PLATFORM, RuntimeSelector.select(settings).status());
        settings.setProperty("os.name", "Mac OS X");
        settings.setProperty("os.arch", "aarch64");
        assertEquals(UNSUPPORTED_PLATFORM, RuntimeSelector.select(settings).status());
    }

    @Test
    void missingInstalledRuntimeKeepsNormalProvider() {
        Properties settings = settings(temporary);
        settings.remove("nvkbridge.runtime");
        assertEquals(UNCONFIGURED, RuntimeSelector.select(settings, temporary.resolve("not-installed")).status());
    }

    @Test
    void automaticallySelectsVerifiedInstalledRuntimeWithoutChangingSettings() throws Exception {
        Path root = makeRuntime("installed runtime", thinDylib(0x01000007));
        Properties settings = settings(temporary);
        settings.remove("nvkbridge.runtime");
        Properties original = (Properties) settings.clone();
        var selection = RuntimeSelector.select(settings, root);
        assertEquals(SELECTED, selection.status());
        assertEquals(root.toRealPath(), selection.runtime().root());
        assertEquals(original, settings);
    }

    @Test
    void blankRuntimePropertyUsesInstalledRuntime() throws Exception {
        Path root = makeRuntime("installed", thinDylib(0x01000007));
        Properties settings = settings(temporary);
        settings.setProperty("nvkbridge.runtime", "  ");
        assertEquals(SELECTED, RuntimeSelector.select(settings, root).status());
    }

    @Test
    void customRuntimeTakesPrecedenceOverInstalledRuntime() throws Exception {
        Path installed = makeRuntime("installed", thinDylib(0x01000007));
        Path custom = makeRuntime("custom", thinDylib(0x01000007));
        var selection = RuntimeSelector.select(settings(custom), installed);
        assertEquals(SELECTED, selection.status());
        assertEquals(custom.toRealPath(), selection.runtime().root());
    }

    @Test
    void invalidCustomRuntimeDoesNotFallBackToInstalledRuntime() throws Exception {
        Path installed = makeRuntime("installed", thinDylib(0x01000007));
        assertEquals(INVALID, RuntimeSelector.select(settings(temporary.resolve("bad-custom-path")), installed).status());
    }

    @Test
    void automaticallyDiscoveredRuntimeStillRequiresValidHash() throws Exception {
        Path root = makeRuntime("installed", thinDylib(0x01000007));
        byte[] changed = thinDylib(0x01000007);
        changed[31] = 1;
        Files.write(root.resolve("lib/libvulkan_nouveau.dylib"), changed);
        Properties settings = settings(temporary);
        settings.remove("nvkbridge.runtime");
        var selection = RuntimeSelector.select(settings, root);
        assertEquals(INVALID, selection.status());
        assertTrue(selection.message().contains("SHA-256"));
    }

    @Test
    void automaticSelectionPreservesExplicitLwjglOverride() throws Exception {
        Path root = makeRuntime("installed", thinDylib(0x01000007));
        Properties settings = settings(temporary);
        settings.remove("nvkbridge.runtime");
        settings.setProperty("org.lwjgl.vulkan.libname", "MoltenVK");
        assertEquals(CONFLICT, RuntimeSelector.select(settings, root).status());
        assertEquals("MoltenVK", settings.getProperty("org.lwjgl.vulkan.libname"));
    }

    @Test
    void disabledSettingPreventsAutomaticSelection() throws Exception {
        Path root = makeRuntime("installed", thinDylib(0x01000007));
        Properties settings = settings(temporary);
        settings.remove("nvkbridge.runtime");
        settings.setProperty("nvkbridge.enabled", "false");
        assertEquals(DISABLED, RuntimeSelector.select(settings, root).status());
    }

    @Test
    void automaticSelectionDoesNotApplyToWindowsOrAppleSilicon() throws Exception {
        Path root = makeRuntime("installed", thinDylib(0x01000007));
        Properties settings = settings(temporary);
        settings.remove("nvkbridge.runtime");
        settings.setProperty("os.name", "Windows 11");
        assertEquals(UNSUPPORTED_PLATFORM, RuntimeSelector.select(settings, root).status());
        settings.setProperty("os.name", "Mac OS X");
        settings.setProperty("os.arch", "aarch64");
        assertEquals(UNSUPPORTED_PLATFORM, RuntimeSelector.select(settings, root).status());
    }

    @Test
    void rejectsHeadlessRuntimeWithoutManifest() {
        assertEquals(INVALID, RuntimeSelector.select(settings(temporary)).status());
    }

    @Test
    void rejectsDifferentMinecraftVersion() throws Exception {
        Path root = makeRuntime("wrong-version", thinDylib(0x01000007));
        edit(root, "minecraft.version", "26.2");
        assertEquals(INVALID, RuntimeSelector.select(settings(root)).status());
    }

    @Test
    void rejectsChangedRuntimeBinary() throws Exception {
        Path root = makeRuntime("changed", thinDylib(0x01000007));
        byte[] bytes = thinDylib(0x01000007);
        bytes[31] = 1;
        Files.write(root.resolve("lib/libvulkan_nouveau.dylib"), bytes);
        var selection = RuntimeSelector.select(settings(root));
        assertEquals(INVALID, selection.status());
        assertTrue(selection.message().contains("SHA-256"));
    }

    @Test
    void rejectsArmOnlyLibraryEvenWithMatchingHash() throws Exception {
        Path root = makeRuntime("arm", thinDylib(0x0100000c));
        assertEquals(INVALID, RuntimeSelector.select(settings(root)).status());
    }

    @Test
    void acceptsUniversalLibraryContainingIntelSlice() throws Exception {
        ByteBuffer fat = ByteBuffer.allocate(64).order(ByteOrder.BIG_ENDIAN);
        fat.putInt(0xcafebabe).putInt(1);
        fat.putInt(0x01000007).putInt(3).putInt(32).putInt(32).putInt(0);
        fat.position(32);
        fat.put(thinDylib(0x01000007));
        Path root = makeRuntime("universal", fat.array());
        assertEquals(SELECTED, RuntimeSelector.select(settings(root)).status());
    }

    @Test
    void rejectsFatSliceOutsideFile() throws Exception {
        ByteBuffer fat = ByteBuffer.allocate(64).order(ByteOrder.BIG_ENDIAN);
        fat.putInt(0xcafebabe).putInt(1);
        fat.putInt(0x01000007).putInt(3).putInt(4096).putInt(32).putInt(0);
        Path root = makeRuntime("bad-fat", fat.array());
        assertEquals(INVALID, RuntimeSelector.select(settings(root)).status());
    }

    @Test
    void preservesExistingExplicitVulkanOverride() throws Exception {
        Path root = makeRuntime("conflict", thinDylib(0x01000007));
        Properties settings = settings(root);
        settings.setProperty("org.lwjgl.vulkan.libname", "MoltenVK");
        assertEquals(CONFLICT, RuntimeSelector.select(settings).status());
        assertEquals("MoltenVK", settings.getProperty("org.lwjgl.vulkan.libname"));
    }

    @Test
    void acceptsExistingOverrideToSameLibrary() throws Exception {
        Path root = makeRuntime("same", thinDylib(0x01000007));
        Properties settings = settings(root);
        settings.setProperty("org.lwjgl.vulkan.libname", root.resolve("lib/libvulkan_nouveau.dylib").toString());
        assertEquals(SELECTED, RuntimeSelector.select(settings).status());
    }

    @Test
    void acceptsExistingOverrideThroughFileSystemAlias() throws Exception {
        Path root = makeRuntime("alias", thinDylib(0x01000007));
        Path alias = temporary.resolve("renderer-alias.dylib");
        Files.createSymbolicLink(alias, root.resolve("lib/libvulkan_nouveau.dylib").toAbsolutePath());
        Properties settings = settings(root);
        settings.setProperty("org.lwjgl.vulkan.libname", alias.toString());
        assertEquals(SELECTED, RuntimeSelector.select(settings).status());
    }

    @Test
    void rejectsLibraryOutsideRuntimeRoot() throws Exception {
        Path root = makeRuntime("escape", thinDylib(0x01000007));
        Files.write(temporary.resolve("outside.dylib"), thinDylib(0x01000007));
        edit(root, "library.path", "../outside.dylib");
        assertEquals(INVALID, RuntimeSelector.select(settings(root)).status());
    }

    @Test
    void permitsExplicitStagingBuildForBringup() throws Exception {
        Path root = makeRuntime("staging", thinDylib(0x01000007));
        edit(root, "presentation", "cpu-staging");
        var selection = RuntimeSelector.select(settings(root));
        assertEquals(SELECTED, selection.status());
        assertEquals("cpu-staging", selection.runtime().presentation());
    }

    @Test
    void rejectsUnsupportedManifestSchemaAndPresentation() throws Exception {
        Path root = makeRuntime("schema", thinDylib(0x01000007));
        edit(root, "schema.version", "2");
        assertEquals(INVALID, RuntimeSelector.select(settings(root)).status());
        edit(root, "schema.version", "1");
        edit(root, "presentation", "zero-copy");
        assertEquals(INVALID, RuntimeSelector.select(settings(root)).status());
    }

    @Test
    void rejectsExecutableDisguisedAsDylib() throws Exception {
        byte[] binary = thinDylib(0x01000007);
        ByteBuffer.wrap(binary).order(ByteOrder.LITTLE_ENDIAN).putInt(12, 2);
        Path root = makeRuntime("executable", binary);
        assertEquals(INVALID, RuntimeSelector.select(settings(root)).status());
    }

    private Properties settings(Path root) {
        Properties settings = new Properties();
        settings.setProperty("os.name", "Mac OS X");
        settings.setProperty("os.arch", "x86_64");
        settings.setProperty("nvkbridge.runtime", root.toString());
        return settings;
    }

    private Path makeRuntime(String name, byte[] binary) throws Exception {
        Path root = temporary.resolve(name);
        Files.createDirectories(root.resolve("lib"));
        Files.write(root.resolve("lib/libvulkan_nouveau.dylib"), binary);
        Properties values = new Properties();
        values.setProperty("schema.version", "1");
        values.setProperty("target.os", "macos");
        values.setProperty("target.arch", "x86_64");
        values.setProperty("minecraft.version", "26.3");
        values.setProperty("runtime.id", "unit-test");
        values.setProperty("library.path", "lib/libvulkan_nouveau.dylib");
        values.setProperty("library.sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(binary)));
        values.setProperty("presentation", "gpu-copy");
        write(root, values);
        return root;
    }

    private void edit(Path root, String key, String value) throws IOException {
        Properties values = new Properties();
        try (var reader = Files.newBufferedReader(root.resolve(RuntimeManifest.FILE_NAME), StandardCharsets.UTF_8)) {
            values.load(reader);
        }
        values.setProperty(key, value);
        write(root, values);
    }

    private void write(Path root, Properties values) throws IOException {
        try (Writer writer = Files.newBufferedWriter(root.resolve(RuntimeManifest.FILE_NAME), StandardCharsets.UTF_8)) {
            values.store(writer, "Synthetic test fixture, not a loadable native library");
        }
    }

    private byte[] thinDylib(int cpuType) {
        return ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0xfeedfacf).putInt(cpuType).putInt(3).putInt(6).array();
    }
}
