"""Describe an existing native runtime; this does not build or validate its WSI."""

import argparse
import hashlib
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runtime", type=Path, required=True)
    parser.add_argument("--library", default="lib/libvulkan_nouveau.dylib")
    parser.add_argument("--id", required=True)
    parser.add_argument("--presentation", choices=("gpu-copy", "cpu-staging"), required=True)
    parser.add_argument("--force", action="store_true")
    args = parser.parse_args()
    root = args.runtime.resolve(strict=True)
    relative = Path(args.library)
    if relative.is_absolute():
        parser.error("--library must be relative to --runtime")
    library = (root / relative).resolve(strict=True)
    if not library.is_file() or not library.is_relative_to(root):
        parser.error("--library must name a file inside --runtime")
    if not args.id.strip() or any(c in args.id for c in "\r\n"):
        parser.error("--id must be a nonempty single line")
    destination = root / "nvk-bridge-runtime.properties"
    if destination.exists() and not args.force:
        parser.error("manifest exists; use --force to replace it")
    with library.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    # Java properties interpret backslashes and a leading space as escapes.
    runtime_id = args.id.strip().replace("\\", "\\\\")
    library_path = library.relative_to(root).as_posix().replace("\\", "\\\\")
    text = (
        "schema.version=1\n"
        "target.os=macos\n"
        "target.arch=x86_64\n"
        "minecraft.version=26.3\n"
        f"runtime.id={runtime_id}\n"
        f"library.path={library_path}\n"
        f"library.sha256={digest}\n"
        f"presentation={args.presentation}\n"
    )
    destination.write_text(text, encoding="utf-8", newline="\n")
    print(f"Wrote {destination}; native presentation remains to be validated")


if __name__ == "__main__":
    main()
