"""Install the built MCP bundles into STM32CubeIDE with p2 director.

Paths are configurable so this works on any machine:
  CUBEIDE_HOME   STM32CubeIDE install dir (default: this machine's V2 path)
  MCP_REPO       staging dir for the extracted p2 repository (default below)
The update-site zip is located relative to this script (server/scripts/ ->
server/update-site/...), so no absolute source path is baked in.

The IDE must be CLOSED while this runs.
"""
import gzip
import glob
import os
import re
import shutil
import subprocess
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
SERVER = os.path.dirname(HERE)
IDE = os.environ.get("CUBEIDE_HOME", r"D:\tools\hardware\STM32CubeIDE-V2\STM32CubeIDE")
EXE = os.path.join(IDE, "stm32cubeidec.exe")
REPO = os.environ.get("MCP_REPO", r"D:\tools\hardware\CubeIDE-mcp-repo")
ZIP = os.path.join(SERVER, "update-site", "com.vogella.eclipse.mcp.repository",
                   "target", "com.vogella.eclipse.mcp.repository.eclipse-repository-3.2.0-SNAPSHOT.zip")
IUS = ["core", "basic", "cdt", "server", "stm32", "ui"]

if not os.path.isfile(ZIP):
    raise SystemExit("update-site zip not found: %s (run mvn clean verify from server/)" % ZIP)
if not os.path.isfile(EXE):
    raise SystemExit("p2 director not found: %s (set CUBEIDE_HOME)" % EXE)


def director(args):
    cmd = [EXE, "-application", "org.eclipse.equinox.p2.director", "-noSplash",
           "-repository", "file:/" + REPO.replace("\\", "/"),
           "-destination", IDE, "-profile", "STM32CubeIDE"] + args
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=300)
    print(r.stdout[-2000:] if r.stdout else "")
    if r.returncode != 0:
        print("STDERR:", r.stderr[-1000:] if r.stderr else "")
    return r


# 1. extract fresh build
if os.path.isdir(REPO):
    shutil.rmtree(REPO)
zipfile.ZipFile(ZIP).extractall(REPO)
new_jars = glob.glob(os.path.join(REPO, "plugins", "com.vogella.eclipse.mcp.*.jar"))
print("new build:", [os.path.basename(j) for j in new_jars])

# 2. uninstall what the PROFILE has registered (disk jars may be orphans)
reg = os.path.join(IDE, "p2", "org.eclipse.equinox.p2.engine", "profileRegistry", "STM32CubeIDE.profile")
prof_files = sorted(glob.glob(os.path.join(reg, "*.profile.gz")), key=os.path.getmtime)
data = gzip.open(prof_files[-1], "rt", encoding="utf-8").read()
pairs = [f"{i}/{v}" for i, v in re.findall(
    r"id=['\"](com\.vogella\.eclipse\.mcp\.[a-z0-9]+)['\"]\s+version=['\"]([^'\"]+)['\"]", data)]
if pairs:
    print("uninstalling (from profile):", pairs)
    director(["-uninstallIU", ",".join(pairs)])
else:
    print("profile has no mcp IUs")

# 3. wipe every mcp jar on disk (orphans included), then install fresh
for jar in glob.glob(os.path.join(IDE, "plugins", "com.vogella.eclipse.mcp.*.jar")):
    os.remove(jar)
director(["-installIU", ",".join("com.vogella.eclipse.mcp." + iu for iu in IUS)])

# 3b. enable auto-start for the stm32 bundle: its eager Activator installs the
# DSF event listener that must be live before any debug session starts. p2 marks
# installed bundles non-auto-start by default, which would never run the Activator.
info = os.path.join(IDE, "configuration", "org.eclipse.equinox.simpleconfigurator", "bundles.info")
with open(info, "r", encoding="utf-8") as f:
    lines = f.readlines()
changed = False
for i, line in enumerate(lines):
    if line.startswith("com.vogella.eclipse.mcp.stm32,") and line.rstrip().endswith(",false"):
        lines[i] = line.rstrip()[:-len(",false")] + ",true\n"
        changed = True
if changed:
    tmp = info + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        f.writelines(lines)
    os.replace(tmp, info)
    print("enabled auto-start for com.vogella.eclipse.mcp.stm32")
else:
    print("stm32 auto-start: no change needed (already true, p2.inf handled it, or entry absent)")

# 4. verify on disk
left = sorted(os.path.basename(j) for j in glob.glob(os.path.join(IDE, "plugins", "com.vogella.eclipse.mcp.*.jar")))
print("on disk now:")
for j in left:
    print(" ", j)
