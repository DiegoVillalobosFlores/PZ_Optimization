#!/usr/bin/env python3
"""Writes the GLSL strings of pzopt.PixelLight (LIGHT_GLSL, SHADOW_FRAG, MASK_BLUR_FRAG, PASS_FRAG, CHUNK_FRAG) to <out>/ for tools/hdr/glslcheck."""
import re
import sys

src = open(sys.argv[1]).read()
out = sys.argv[2]
consts = {}
for name in ("LIGHT_GLSL", "SHADOW_FRAG", "MASK_BLUR_FRAG", "PASS_FRAG", "CHUNK_FRAG_BODY", "CHUNK_BASE_VERT"):
    m = re.search(r'String ' + name + r' = String\.join\("\\n",\n(.*?)\);\n', src, re.S)
    body = m.group(1)
    parts = []
    for line in body.split("\n"):
        line = line.strip()
        if not line or line.startswith("//"):
            continue
        lit = re.match(r'"((?:[^"\\]|\\.)*)"\s*,?\s*(//.*)?$', line)
        if lit:
            parts.append(lit.group(1).encode().decode("unicode_escape"))
        else:
            ident = line.rstrip(",").strip()
            parts.append(consts[ident])
    consts[name] = "\n".join(parts)
    open(f"{out}/{name}.glsl", "w").write(consts[name] + "\n")
open(f"{out}/CHUNK_FRAG.glsl", "w").write("#version 420\n" + consts["CHUNK_FRAG_BODY"] + "\n")
open(f"{out}/CHUNK_DEV_FRAG.glsl", "w").write("#version 420\n#define PPL_DEV\n" + consts["CHUNK_FRAG_BODY"] + "\n")
open(f"{out}/CHUNK_BASE_FRAG.glsl", "w").write("#version 420\n#define PPL_BASE\n" + consts["CHUNK_FRAG_BODY"] + "\n")
print("ok")
