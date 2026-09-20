"""Package source and the locally built Maven repository; never include local config/caches."""
from pathlib import Path
import hashlib
import shutil
import zipfile

root = Path(__file__).resolve().parents[1]
dist = root / "dist"
dist.mkdir(exist_ok=True)
excluded = {"build", "dist", ".gradle", ".kotlin", ".git", ".idea", "__pycache__"}
source_files = [
    p for p in root.rglob("*")
    if p.is_file()
    and not any(part in excluded for part in p.relative_to(root).parts)
    and p.name != "local.properties"
    and not p.name.startswith(".env")
    and p.suffix not in {".jks", ".keystore", ".iml"}
]
with zipfile.ZipFile(dist / "jev-android-0.3.5-source.zip", "w", zipfile.ZIP_DEFLATED) as archive:
    for path in sorted(source_files):
        info = zipfile.ZipInfo.from_file(path, str(Path("jev-android") / path.relative_to(root)))
        if path.name == "gradlew":
            info.create_system = 3
            info.external_attr = 0o100755 << 16
        archive.writestr(info, path.read_bytes(), compress_type=zipfile.ZIP_DEFLATED)

repository = root / "build" / "repository"
if not repository.is_dir():
    raise SystemExit("Build Maven publications first; see README")
with zipfile.ZipFile(dist / "jev-android-0.3.5-maven.zip", "w", zipfile.ZIP_DEFLATED) as archive:
    for path in sorted(repository.rglob("*")):
        if path.is_file():
            archive.write(path, Path("jev-maven") / path.relative_to(repository))
shutil.copy2(root / "sample/build/outputs/apk/debug/sample-debug.apk", dist / "jev-android-demo-0.3.5.apk")
outputs = sorted(p for p in dist.iterdir() if p.suffix in {".zip", ".apk"})
(dist / "SHA256SUMS.txt").write_text("".join(
    f"{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.name}\n" for p in outputs
), encoding="utf-8")
for path in outputs:
    print(f"{path.name}: {path.stat().st_size} bytes")
