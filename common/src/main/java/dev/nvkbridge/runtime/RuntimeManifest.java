package dev.nvkbridge.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Properties;

public record RuntimeManifest(Path root, Path library, String runtimeId, String presentation, String sha256) {
    public static final String FILE_NAME = "nvk-bridge-runtime.properties";

    public static RuntimeManifest load(Path directory) throws IOException {
        Path root = directory.toRealPath();
        Path manifest = root.resolve(FILE_NAME);
        if (!Files.isRegularFile(manifest) || Files.size(manifest) > 65536) {
            throw new IOException("Missing or oversized " + FILE_NAME);
        }
        Properties values = new Properties();
        try (Reader reader = Files.newBufferedReader(manifest, StandardCharsets.UTF_8)) {
            values.load(reader);
        }
        requireValue(values, "schema.version", "1");
        requireValue(values, "target.os", "macos");
        requireValue(values, "target.arch", "x86_64");
        requireValue(values, "minecraft.version", "26.3");
        String id = required(values, "runtime.id");
        String presentation = required(values, "presentation");
        if (!presentation.equals("gpu-copy") && !presentation.equals("cpu-staging")) {
            throw new IOException("Unsupported presentation mode: " + presentation);
        }
        Path relativeLibrary = Path.of(required(values, "library.path"));
        if (relativeLibrary.isAbsolute()) throw new IOException("library.path must be relative to the runtime root");
        Path library = root.resolve(relativeLibrary).normalize().toRealPath();
        if (!library.startsWith(root) || !Files.isRegularFile(library)) {
            throw new IOException("library.path must identify a file inside the runtime root");
        }
        String expectedHash = required(values, "library.sha256").toLowerCase(java.util.Locale.ROOT);
        if (!expectedHash.matches("[0-9a-f]{64}")) throw new IOException("library.sha256 must contain 64 hexadecimal digits");
        if (!MachO.isX86Dylib(library)) throw new IOException("The Vulkan library is not an x86_64 Mach-O dylib");
        String actualHash = hash(library);
        if (!expectedHash.equals(actualHash)) throw new IOException("Vulkan library SHA-256 differs from the runtime manifest");
        return new RuntimeManifest(root, library, id, presentation, actualHash);
    }

    private static void requireValue(Properties values, String key, String expected) throws IOException {
        if (!required(values, key).equals(expected)) throw new IOException("Runtime requires " + key + "=" + expected);
    }

    private static String required(Properties values, String key) throws IOException {
        String value = values.getProperty(key, "").trim();
        if (value.isEmpty()) throw new IOException("Runtime manifest is missing " + key);
        return value;
    }

    private static String hash(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream stream = Files.newInputStream(file)) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = stream.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Java runtime has no SHA-256 provider", impossible);
        }
    }
}
