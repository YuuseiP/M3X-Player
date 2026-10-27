"""Download build/runtime test tools from their publishers, without changing existing SDK files."""
import hashlib
import os
from pathlib import Path
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
import argparse

ROOT = Path(__file__).resolve().parent
TOOLS = ROOT / ".tools"
TOOLS.mkdir(exist_ok=True)
SDK = Path(os.environ.get("ANDROID_HOME", str(Path.home() / "AppData/Local/Android/Sdk")))


def download(url, name, digest="", algorithm="sha256"):
    target = TOOLS / name
    if not target.exists():
        print("Downloading", name, flush=True)
        urllib.request.urlretrieve(url, target)
    if digest and hashlib.file_digest(target.open("rb"), algorithm).hexdigest() != digest:
        raise ValueError("Checksum mismatch: " + name)
    return target


def setup(emulator=False):
    url = "https://services.gradle.org/distributions/gradle-8.9-bin.zip"
    digest = urllib.request.urlopen(url + ".sha256", timeout=30).read().decode().strip()
    archive = download(url, "gradle-8.9-bin.zip", digest)
    if not (TOOLS / "gradle-8.9").exists():
        with zipfile.ZipFile(archive) as zipped:
            zipped.extractall(TOOLS)
    (ROOT / "local.properties").write_text("sdk.dir=" + str(SDK).replace("\\", "/") + "\n", encoding="utf-8")
    if not emulator:
        print("Build tools ready:", TOOLS, flush=True)
        return
    repository = ET.fromstring(urllib.request.urlopen("https://dl.google.com/android/repository/sys-img/android/sys-img2-1.xml", timeout=30).read())
    package = next(item for item in repository.findall("remotePackage") if item.attrib["path"] == "system-images;android-25;default;x86_64")
    image = package.find("archives/archive/complete")
    archive = download("https://dl.google.com/android/repository/sys-img/android/" + image.findtext("url"), "api25-x86_64.zip", image.findtext("checksum"), "sha1")
    image_dir = TOOLS / "system-image"
    if not image_dir.exists():
        with zipfile.ZipFile(archive) as zipped:
            zipped.extractall(image_dir)
    avds = TOOLS / "avd"
    avd = avds / "M3X_API25.avd"
    avd.mkdir(parents=True, exist_ok=True)
    (avds / "M3X_API25.ini").write_text("avd.ini.encoding=UTF-8\npath=" + str(avd) + "\ntarget=android-25\n", encoding="utf-8")
    (avd / "config.ini").write_text(
        "AvdId=M3X_API25\navd.ini.displayname=M3X API 25\nhw.cpu.arch=x86_64\nhw.cpu.ncore=2\n"
        "hw.ramSize=1536\nhw.lcd.width=768\nhw.lcd.height=1280\nhw.lcd.density=320\n"
        "hw.keyboard=yes\nhw.gpu.enabled=yes\nhw.gpu.mode=swiftshader_indirect\nhw.sdCard=yes\n"
        "disk.dataPartition.size=3G\nabi.type=x86_64\nimage.sysdir.1=" + str(image_dir / "x86_64") + "\n",
        encoding="utf-8")
    print("Tools ready:", TOOLS, flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--emulator", action="store_true", help="Also prepare the Android 7.1 test emulator")
    setup(parser.parse_args().emulator)
