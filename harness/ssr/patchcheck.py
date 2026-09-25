#!/usr/bin/env python3
"""Rebuilds pzopt.Ssr's patched water shaders (on the stock files and on the HDR-patched ones that tools/hdr/patchcheck.py
writes to /tmp/pz_<name>_patched.frag) and compiles them with the driver (/tmp/glslcheck from tools/hdr/glslcheck.c:
cc -O2 -o /tmp/glslcheck tools/hdr/glslcheck.c -lEGL -lGL). Run from the repo root; writes /tmp/pz_ssr_<name>*.frag."""
import os, re, subprocess, sys

S = '/games/steamapps/common/ProjectZomboid/projectzomboid/media/shaders'
java = open('src/pzopt/pzopt/Ssr.java').read()


def const(name):
    """a String constant of Ssr.java: [a "literal" +] [ANOTHER_CONSTANT +] String.join("\\n", lines...)"""
    m = re.search(r'\b' + name + r' = ((?:"(?:[^"\\]|\\.)*" \+ )?)((?:[A-Z_]+ \+ )?)String.join\("\\n",(.*?)\);\n', java, re.S)
    head = m.group(1)[1:-4].encode().decode('unicode_escape') if m.group(1) else ""
    other = const(m.group(2)[:-3]) if m.group(2) else ""
    return head + other + "\n".join(l.encode().decode('unicode_escape') for l in re.findall(r'"((?:[^"\\]|\\.)*)"', m.group(3))) + "\n"


def patch(code):  # Ssr.patchShader
    anchor = "gl_FragDepth = vDepth;"
    at, mi = code.index(anchor), code.index("void mainImage(")
    return ("#version 150 compatibility\n#extension GL_ARB_shader_image_load_store : require\n#define PZ_SSR_PPR" + code[len("#version 120"):mi] + const("WATER_GLSL") + "\n" + code[mi:at + len(anchor)]
            + "\n    fragColor = pzSsrApply(fragColor, gm);" + code[at + len(anchor):])


if subprocess.call([sys.executable, 'tools/hdr/patchcheck.py'], stdout=subprocess.DEVNULL) != 0:
    print("(tools/hdr/patchcheck.py reported a failure of its own)")
rc = 0
for name in ('water', 'water_hq'):
    for tag, src in (('stock', S + '/%s.frag' % name), ('hdr', '/tmp/pz_%s_patched.frag' % name)):
        if not os.path.exists(src):
            continue
        out = '/tmp/pz_ssr_%s_%s.frag' % (name, tag)
        open(out, 'w').write(patch(open(src).read()))
        r = subprocess.run(['/tmp/glslcheck', S + '/%s.vert' % name, out], capture_output=True, text=True)
        print(r.stdout.strip())
        rc |= r.returncode != 0 or 'FAILED' in r.stdout
# the chunk composite with the scatter (Ssr.patchComposite)
src = open(S + '/chunkShader.frag').read()
c = ("#version 150 compatibility\n#extension GL_ARB_shader_image_load_store : require\n#define PZ_SSR_OUT gl_FragColor" + src[len("#version 120"):].replace("void main()", "void pzChunkMain()")
     + "\n" + const("SCATTER_GLSL") + "\n")
open('/tmp/pz_ssr_chunk.frag', 'w').write(c)
r = subprocess.run(['/tmp/glslcheck', S + '/chunkShader.vert', '/tmp/pz_ssr_chunk.frag'], capture_output=True, text=True)
print(r.stdout.strip())
rc |= r.returncode != 0 or 'FAILED' in r.stdout
# pixelLight's composite (4.20 core, out fragColor) is assembled from several constants: its patch is test-compiled in
# the game (Ssr.patchComposite logs a warning and keeps it unpatched if it fails)
# puddles (Ssr.patchPuddles on the puddleEarlyZ common unit, HDR's glint patch in too via tools/hdr/patchcheck.py's copy)
def units(path):
    main, extra = [], []
    for line in open(path).read().splitlines():
        m = re.match(r'\s*#include "([^"]+)"', line)
        if m:
            base = S + '/' + m.group(1)
            main.append(open(base + '.h').read())
            extra.append(open(base + '.glsl').read())
        else:
            main.append(line)
    return "\n".join(main), extra
for q in ('pzopt_puddles_hq', 'pzopt_puddles_mq'):
    fm, fx = units(S + '/%s.frag' % q)
    fm = "#version 150 compatibility" + fm[len("#version 120"):]
    common = fx[0]
    sm = common.index("vec2 SphereMap(")
    anchor = "vec3 reflection = fragColor.rgb;"
    common = ("#version 150 compatibility\n#extension GL_ARB_shader_image_load_store : require\n#define PZ_SSR_PPR" + common[len("#version 120"):sm]
              + const("WATER_GLSL") + "\n" + common[sm:]).replace(anchor, anchor + "\n    if (alphaPuddlesAmbient + alphaPuddlesReflection > 0.02) reflection = pzSsrPuddle(reflection, gm);")
    files = []
    for i, t in enumerate([fm, common]):
        open('/tmp/pz_ssr_%s_f%d.frag' % (q, i), 'w').write(t)
        files.append('/tmp/pz_ssr_%s_f%d.frag' % (q, i))
    vm, vx = units(S + '/%s.vert' % q)
    open('/tmp/pz_ssr_%s.vert' % q, 'w').write(vm + '\n' + '\n'.join(re.sub(r'#version.*', '', x) for x in vx))
    r = subprocess.run(['/tmp/glslcheck', '/tmp/pz_ssr_%s.vert' % q] + files, capture_output=True, text=True)
    for line in r.stdout.splitlines():
        if '.frag: compile' in line or 'error' in line.lower():
            print(line)
            rc |= 'FAILED' in line
# the moving-object scatter (own program)
open('/tmp/pz_ssr_moving.vert', 'w').write(const("MOVING_VERT"))
open('/tmp/pz_ssr_moving.frag', 'w').write(const("MOVING_FRAG"))
r = subprocess.run(['/tmp/glslcheck', '/tmp/pz_ssr_moving.vert', '/tmp/pz_ssr_moving.frag'], capture_output=True, text=True)
print(r.stdout.strip())
rc |= r.returncode != 0 or 'FAILED' in r.stdout
sys.exit(rc)
