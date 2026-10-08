"""Packs a BackroomsCraft release into dist/:

    backroomscraft-<version>.jar            the mod
    BackroomsCraft-Minecraft-<version>.zip  a portable Prism Launcher with a ready "BackroomsCraft" instance
                                            (Minecraft 1.21.11, Fabric Loader, Fabric API)

Melty unpacks the zip into its managed folder and drops the jar into the instance's mods folder. Prism asks the
player to sign in once, then downloads Minecraft and Java itself. Nothing of Escape the Backrooms is in either file.

    python package.py        (build the mod first: cd mod && gradlew build)
"""
import hashlib
import json
import os
import re
import shutil
import urllib.request
import zipfile

ROOT = os.path.dirname(os.path.abspath(__file__))
CACHES = [os.path.join(ROOT, "tools", "package-cache"), os.path.join(os.path.dirname(ROOT), "Bodycam + Minecraft", "tools", "package-cache")]
DIST = os.path.join(ROOT, "dist")
INSTANCE = "BackroomsCraft"
MODID = "backroomscraft"

MINECRAFT = "1.21.11"
FABRIC_LOADER = "0.19.5"
LWJGL = "3.3.3"
PRISM_VERSION = "11.1.1"
PRISM_ZIP = "PrismLauncher-Windows-MSVC-Portable-%s.zip" % PRISM_VERSION
PRISM_URL = "https://github.com/PrismLauncher/PrismLauncher/releases/download/%s/%s" % (PRISM_VERSION, PRISM_ZIP)
PRISM_SHA256 = "ab35a770fb06d89d2ccc098079db5db329fb4e68f42b72babd8b095efde3d2d7"
PRISM_LICENSE_URL = "https://raw.githubusercontent.com/PrismLauncher/PrismLauncher/%s/LICENSE" % PRISM_VERSION
FABRIC_API_JAR = "fabric-api-0.141.6+1.21.11.jar"
FABRIC_API_URL = "https://cdn.modrinth.com/data/P7dR8mSH/versions/6qAuTtLR/fabric-api-0.141.6%2B1.21.11.jar"

INSTANCE_CFG = """[General]
InstanceType=OneSix
name=Steve in the Backrooms
iconKey=default
notes=Managed by BackroomsCraft on Melty. Updated with each release (the mods folder's BackroomsCraft and Fabric API jars are replaced).
OverrideMemory=true
MinMemAlloc=1024
MaxMemAlloc=4096
OverrideConsole=true
ShowConsole=false
AutoCloseConsole=false
ShowConsoleOnError=true
"""

PRISM_CFG = """[General]
Language=en_US
ApplicationTheme=dark
IconTheme=pe_colored
AutomaticJavaDownload=true
AutomaticJavaSwitch=true
UserAskedAboutAutomaticJavaDownload=true
CloseAfterLaunch=true
QuitAfterGameStop=true
ShowConsole=false
AutoCloseConsole=false
ShowConsoleOnError=true
LowMemWarning=false
"""

MMC_PACK = {
    "components": [
        {"cachedName": "LWJGL 3", "cachedVersion": LWJGL, "cachedVolatile": True, "dependencyOnly": True, "uid": "org.lwjgl3", "version": LWJGL},
        {"cachedName": "Minecraft", "cachedRequires": [{"suggests": LWJGL, "uid": "org.lwjgl3"}], "cachedVersion": MINECRAFT,
         "important": True, "uid": "net.minecraft", "version": MINECRAFT},
        {"cachedName": "Intermediary Mappings", "cachedRequires": [{"equals": MINECRAFT, "uid": "net.minecraft"}], "cachedVersion": MINECRAFT,
         "cachedVolatile": True, "dependencyOnly": True, "uid": "net.fabricmc.intermediary", "version": MINECRAFT},
        {"cachedName": "Fabric Loader", "cachedRequires": [{"uid": "net.fabricmc.intermediary"}], "cachedVersion": FABRIC_LOADER,
         "uid": "net.fabricmc.fabric-loader", "version": FABRIC_LOADER},
    ],
    "formatVersion": 1,
}

THIRD_PARTY = """BackroomsCraft ships this copy of Minecraft's launcher setup so Melty can start the game in one click.
It doesn't include Minecraft: Prism Launcher downloads it after you sign in with a Microsoft account that owns
Minecraft: Java Edition. It doesn't include anything of Escape the Backrooms either: the mod reads the level, the
entity, the items and the sounds from the copy of Escape the Backrooms installed on your PC.

Prism Launcher %(prism)s (this folder, the unmodified portable Windows build)
  License: GNU General Public License v3.0 (LICENSE-PrismLauncher.txt)
  Source:  https://github.com/PrismLauncher/PrismLauncher/tree/%(prism)s

Fabric API (instances/BackroomsCraft/.minecraft/mods/%(fabric_api)s)
  License: Apache License 2.0
  Source:  https://github.com/FabricMC/fabric

BackroomsCraft (instances/BackroomsCraft/.minecraft/mods/backroomscraft-*.jar, installed by Melty)
  License: MIT. It bundles no third-party code.
"""

README = """Steve in the Backrooms
======================
Escape the Backrooms' real Level 0, played as Minecraft's Steve. The map, the Bacteria, the flashlight and the almond
water are read from your own copy of Escape the Backrooms.

You need Minecraft: Java Edition and Escape the Backrooms installed on Steam. Nothing of Escape the Backrooms is in
this package.

Melty installs and starts everything. The first time, Prism Launcher opens once: sign in with the Microsoft account
that owns Minecraft. Then create a single-player Survival world: the mod builds Level 0 from your copy of the game
(a few seconds, the first time only) and puts you in it.

Right click with the flashlight switches it on and off. Right click with the almond water drinks it.
Find the level's exit vent before the Bacteria finds you.
"""


def fetch(url, name, sha256=None):
    path = next((os.path.join(c, name) for c in CACHES if os.path.isfile(os.path.join(c, name))), os.path.join(CACHES[0], name))
    if not os.path.isfile(path):
        os.makedirs(CACHES[0], exist_ok=True)
        request = urllib.request.Request(url, headers={"User-Agent": "BackroomsCraft-packager"})
        with urllib.request.urlopen(request) as response, open(path, "wb") as out:
            shutil.copyfileobj(response, out)
    if sha256 and hashlib.sha256(open(path, "rb").read()).hexdigest() != sha256:
        raise SystemExit("%s doesn't match its pinned hash" % name)
    return path


def main():
    version = re.search(r"^version=(.+)$", open(os.path.join(ROOT, "mod", "gradle.properties")).read(), re.M).group(1).strip()
    jar = os.path.join(ROOT, "mod", "build", "libs", "%s-%s.jar" % (MODID, version))
    if not os.path.isfile(jar):
        raise SystemExit("missing %s: build the mod first" % jar)
    with zipfile.ZipFile(jar) as z:
        leaked = [n for n in z.namelist() if re.search(r"\.(uasset|umap|uexp|ubulk|pak|ogg)$", n, re.I)]
        if leaked:
            raise SystemExit("the jar contains game files: %s" % leaked[:3])
    prism = fetch(PRISM_URL, PRISM_ZIP, PRISM_SHA256)
    fabric_api = fetch(FABRIC_API_URL, FABRIC_API_JAR)
    prism_license = fetch(PRISM_LICENSE_URL, "PrismLauncher-%s-LICENSE.txt" % PRISM_VERSION)

    if os.path.isdir(DIST):
        shutil.rmtree(DIST)
    os.makedirs(DIST)
    shutil.copy(jar, os.path.join(DIST, os.path.basename(jar)))
    bundle = os.path.join(DIST, "%s-Minecraft-%s.zip" % (INSTANCE, version))
    instance = "Prism/instances/%s/" % INSTANCE
    with zipfile.ZipFile(bundle, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as out:
        with zipfile.ZipFile(prism) as source:
            for info in source.infolist():
                if not info.is_dir():
                    out.writestr("Prism/" + info.filename, source.read(info))
        out.writestr("Prism/prismlauncher.cfg", PRISM_CFG)
        out.writestr("Prism/LICENSE-PrismLauncher.txt", open(prism_license, "rb").read())
        out.writestr("Prism/THIRD-PARTY.txt", THIRD_PARTY % {"prism": PRISM_VERSION, "fabric_api": FABRIC_API_JAR})
        out.writestr(instance + "instance.cfg", INSTANCE_CFG)
        out.writestr(instance + "mmc-pack.json", json.dumps(MMC_PACK, indent=4))
        out.write(fabric_api, instance + ".minecraft/mods/" + FABRIC_API_JAR)
        out.writestr("README.txt", README)
        out.writestr("bundle-version.txt", "BackroomsCraft %s, Prism Launcher %s, %s" % (version, PRISM_VERSION, FABRIC_API_JAR))

    manifest = {}
    for name in sorted(os.listdir(DIST)):
        path = os.path.join(DIST, name)
        manifest[name] = {"size": os.path.getsize(path), "sha256": hashlib.sha256(open(path, "rb").read()).hexdigest()}
        print("%-46s %12d bytes  %s" % (name, manifest[name]["size"], manifest[name]["sha256"]))
    json.dump(manifest, open(os.path.join(DIST, "manifest.json"), "w"), indent=2)


if __name__ == "__main__":
    main()
