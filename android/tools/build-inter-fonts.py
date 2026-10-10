#!/usr/bin/env python3
"""Regenerate the Inter font resources in app/src/main/res/font/.

The web app self-hosts Inter from fonts/inter/ at the repository root. The APK
uses the same typeface, cut from the very same file: InterVariable.woff2, the
variable font the web pages name first in their font stack. Compose wants one
file per weight, so this script pins the variable font to four static
instances, at the text optical size (opsz 14), one per weight the app uses:

    inter_regular.ttf   400   FontWeight.Normal
    inter_medium.ttf    500   FontWeight.Medium
    inter_semibold.ttf  600   FontWeight.SemiBold
    inter_bold.ttf      700   FontWeight.Bold

No italic: no screen in the app sets one. Add an instance here, and a Font()
line in ui/Theme.kt, if that changes.

    pip install fonttools brotli
    python3 tools/build-inter-fonts.py ../fonts/inter/InterVariable.woff2 app/src/main/res/font

Paths are relative to the android/ directory. The output is deterministic: the
same source gives byte-identical files on every run and in every Labs app, so
a regenerated resource that differs from the committed one means the source
changed. The script refuses any source other than the reviewed one (by
SHA-256); after reviewing a new Inter release, update SOURCE_SHA256 and
SOURCE_VERSION together and regenerate.

Inter is licensed under the SIL Open Font License 1.1, which has no Reserved
Font Name, so a modified version may keep the name. Each instance keeps the
copyright, licence and licence URL records (name IDs 0, 13 and 14) of the
source, which is how the OFL travels inside the APK. The repository-level
notice is in NOTICE (LICENSE in MoonApp).
"""
import hashlib
import os
import sys

from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

SOURCE_SHA256 = "693b77d4f32ee9b8bfc995589b5fad5e99adf2832738661f5402f9978429a8e3"
SOURCE_VERSION = "Version 4.001;git-9221beed3"

OPTICAL_SIZE = 14
INSTANCES = [
    # (resource name, wght, style name)
    ("inter_regular", 400, "Regular"),
    ("inter_medium", 500, "Medium"),
    ("inter_semibold", 600, "SemiBold"),
    ("inter_bold", 700, "Bold"),
]


def sha256(path):
    with open(path, "rb") as fh:
        return hashlib.sha256(fh.read()).hexdigest()


def rename(font, style):
    """Name the instance the way the official static Inter cuts are named."""
    name = font["name"]
    version = name.getDebugName(5).removeprefix("Version ")
    ribbi = style in ("Regular", "Bold")
    records = {
        1: "Inter" if ribbi else f"Inter {style}",
        2: style if ribbi else "Regular",
        3: f"{version};RSMS;Inter-{style};instance of InterVariable opsz {OPTICAL_SIZE}",
        4: f"Inter {style}",
        6: f"Inter-{style}",
    }
    if not ribbi:
        records[16] = "Inter"
        records[17] = style
    for rec in list(name.names):
        if rec.nameID in (1, 2, 3, 4, 6, 16, 17, 25):
            name.removeNames(nameID=rec.nameID)
    for name_id, text in records.items():
        name.setName(text, name_id, 3, 1, 0x409)
        name.setName(text, name_id, 1, 0, 0)


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    source, out_dir = sys.argv[1], sys.argv[2]

    digest = sha256(source)
    if digest != SOURCE_SHA256:
        sys.exit(
            f"{source}: SHA-256 {digest} is not the reviewed InterVariable.woff2 "
            f"({SOURCE_SHA256}). Review the new release, then update SOURCE_SHA256 "
            "and SOURCE_VERSION."
        )
    probe = TTFont(source)
    version = probe["name"].getDebugName(5)
    if version != SOURCE_VERSION:
        sys.exit(f"{source}: version {version!r}, expected {SOURCE_VERSION!r}")
    print(f"source  {digest}  {source}  ({version})")

    os.makedirs(out_dir, exist_ok=True)
    for res_name, weight, style in INSTANCES:
        font = TTFont(source, recalcTimestamp=False)
        font.flavor = None  # woff2 in, plain TrueType out
        font = instancer.instantiateVariableFont(
            font, {"wght": weight, "opsz": OPTICAL_SIZE}, inplace=False
        )
        if "STAT" in font:
            del font["STAT"]  # meaningless once no axis is left, and absent from the official statics
        rename(font, style)
        # The style bits must agree with the name: the instancer copies the variable
        # font's Regular bits into every instance, so without this the Bold cut said
        # "Regular" in OS/2.fsSelection and head.macStyle. Now Bold carries the BOLD
        # bit and macStyle bold, the others keep REGULAR, as in the official statics.
        instancer.setRibbiBits(font)
        font.flavor = None
        path = os.path.join(out_dir, f"{res_name}.ttf")
        font.save(path, reorderTables=True)
        print(f"{weight}     {sha256(path)}  {path}  ({os.path.getsize(path):,} bytes)")


if __name__ == "__main__":
    main()
