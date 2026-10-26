"""Check mod packaging; optionally acquire the exact official client for hook checks."""

import argparse
import hashlib
import json
import re
from pathlib import Path
import tomllib
import urllib.request
import zipfile


ROOT = Path(__file__).resolve().parents[1]
CLIENT_SHA1 = "e877b6a07acd633fb3bb475002175cec036e7b87"
CLIENT_URL = f"https://piston-data.mojang.com/v1/objects/{CLIENT_SHA1}/client.jar"
VERSION = re.search(r"version\s*=\s*'([^']+)'", (ROOT / 'build.gradle').read_text()).group(1)


def check_release(loader):
    jar = ROOT / "dist" / f"nvk-bridge-{loader}-26.3-{VERSION}.jar"
    with zipfile.ZipFile(jar) as archive:
        names = set(archive.namelist())
        required = {
            "LICENSE",
            "nvkbridge.mixins.json",
            "dev/nvkbridge/runtime/NvkBridge.class",
            "dev/nvkbridge/runtime/RuntimeSelector.class",
            "dev/nvkbridge/runtime/RuntimeManifest.class",
            "dev/nvkbridge/runtime/OpenGlWarning.class",
            "dev/nvkbridge/mixin/NativeLibrariesBootstrapMixin.class",
            "dev/nvkbridge/mixin/GraphicsBackendWarningMixin.class",
        }
        assert required <= names, f"{loader}: missing shared code or Mixin config"
        assert archive.read("LICENSE") == (ROOT / "LICENSE").read_bytes(), "Packaged license differs from repository license"
        assert not any(n.startswith(("org/lwjgl/", "org/spongepowered/", "net/minecraft/", "net/fabricmc/", "net/neoforged/")) for n in names), "Dependencies were bundled"
        assert not any(n.startswith("META-INF/services/org.spongepowered") for n in names), "Test-only Mixin service was bundled"
        mixin = json.loads(archive.read("nvkbridge.mixins.json"))
        assert mixin["required"] and mixin["client"] == ["NativeLibrariesBootstrapMixin", "GraphicsBackendWarningMixin"]
        if loader == "fabric":
            metadata = json.loads(archive.read("fabric.mod.json"))
            assert metadata["id"] == "nvkbridge"
            assert metadata["version"] == VERSION
            assert metadata["license"] == "MIT"
            assert metadata["environment"] == "client"
            assert metadata["depends"]["minecraft"] == "=26.3"
            assert metadata["depends"]["java"] == ">=25"
            assert "nvkbridge.mixins.json" in metadata["mixins"]
            entry = metadata["entrypoints"]["client"][0].replace(".", "/") + ".class"
        else:
            metadata = tomllib.loads(archive.read("META-INF/neoforge.mods.toml").decode())
            assert metadata["license"] == "MIT"
            assert metadata["mods"][0]["modId"] == "nvkbridge"
            assert metadata["mods"][0]["version"] == VERSION
            assert metadata["mixins"][0]["config"] == "nvkbridge.mixins.json"
            deps = metadata["dependencies"]["nvkbridge"]
            assert any(d["modId"] == "minecraft" and d["versionRange"] == "[26.3]" for d in deps)
            entry = "dev/nvkbridge/neoforge/NvkBridgeNeoForge.class"
        assert entry in names, f"{loader}: missing loader entrypoint"
        for name in required | {entry}:
            if name.endswith(".class"):
                assert int.from_bytes(archive.read(name)[6:8], "big") == 69, f"{name}: wrong Java class version"
    print(f"PASS {loader}: shared code, metadata, client Mixin, Java 25; SHA-256 {hashlib.sha256(jar.read_bytes()).hexdigest()}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--download-client", action="store_true")
    args = parser.parse_args()
    for loader in ("fabric", "neoforge"):
        check_release(loader)
    if args.download_client:
        cache = ROOT / ".cache"
        cache.mkdir(exist_ok=True)
        client = cache / "minecraft-26.3-client.jar"
        if not client.exists():
            with urllib.request.urlopen(CLIENT_URL, timeout=60) as response:
                data = response.read()
            assert hashlib.sha1(data).hexdigest() == CLIENT_SHA1, "Official client SHA-1 differs from pinned target"
            client.write_bytes(data)
        assert hashlib.sha1(client.read_bytes()).hexdigest() == CLIENT_SHA1, "Cached client identity mismatch"
        print("PASS official Minecraft 26.3 client identity; run :common:verifyMinecraftHooks next")


if __name__ == "__main__":
    main()
