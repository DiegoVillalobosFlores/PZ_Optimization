#!/usr/bin/env python3
"""Rebuilds pzopt.Hdr's patched copy of a game shader (screen.frag: the #include expanded to util/math.h, stock main
renamed, Hdr.WORLD_GLSL appended) and compiles it with the driver (/tmp/glslcheck from tools/hdr/glslcheck.c)."""
import os, re, subprocess, sys
S = '/games/steamapps/common/ProjectZomboid/projectzomboid/media/shaders'
java = open('src/pzopt/pzopt/Hdr.java').read()
def const(name):
    m = re.search(name + r' = String.join\("\\n",(.*?)\);\n\n', java, re.S)
    parts = []
    for lit, ident in re.findall(r'"((?:[^"\\]|\\.)*)"|\b([A-Z][A-Z_]+_GLSL)\b', m.group(1)):
        parts.append(const(ident).rstrip("\n") if ident else lit.encode().decode('unicode_escape'))
    return "\n".join(parts) + "\n"
src = open(S + '/screen.frag').read().replace('#include "util/math"', open(S + '/util/math.h').read())
open('/tmp/screen_patched.frag', 'w').write(src.replace("void main()", "void pzStockMain()") + const("WORLD_GLSL"))
rc = subprocess.call(['/tmp/glslcheck', S + '/screen.vert', '/tmp/screen_patched.frag', S + '/util/math.glsl'])
open('/tmp/pz_fullscreen.vert', 'w').write(const('FULLSCREEN_VERT'))
open('/tmp/pz_quad120.vert', 'w').write(const('QUAD120_VERT'))
for frag in re.findall(r'static final String ([A-Z0-9_]+_FRAG) = String.join', java):  # pzopt's own passes
    text = const(frag)
    open('/tmp/pz_%s.frag' % frag, 'w').write(text)
    vert = '/tmp/pz_quad120.vert' if text.startswith('#version 120') else '/tmp/pz_fullscreen.vert'
    rc |= subprocess.call(['/tmp/glslcheck', vert, '/tmp/pz_%s.frag' % frag])
# water (Hdr.patchWater): same replacements as the Java
for name in ('water', 'water_hq'):
    src = open(S + '/%s.frag' % name).read()
    anchor = "fragColor.a = min(levelf, 1.0);"
    c = src.replace("gl_FragColor", "gl_FragData[0]")
    c = c.replace("void mainImage(", const('SURFACE_GLSL') + "\nvoid mainImage(")
    c = c.replace(anchor, anchor + "\n    pzGlint = pzHdrWaterGlint(gm, pzWindowPx()) * fragColor.a;")
    c = c.replace("void main()", "void pzWaterMain()")
    c += "\nvoid main() {\n  pzWaterMain();\n  gl_FragData[1] = vec4(pzGlint / (1.0 + pzGlint), pzSurfNow());\n}\n"
    open('/tmp/pz_%s_patched.frag' % name, 'w').write(c)
    rc |= subprocess.call(['/tmp/glslcheck', S + '/%s.vert' % name, '/tmp/pz_%s_patched.frag' % name])
# puddles (Hdr.patchPuddles): the game inlines an #include's .h and links its .glsl as another unit
def units(path, patch_glsl=None):
    main, extra = [], []
    for line in open(path).read().splitlines():
        m = re.match(r'\s*#include "([^"]+)"', line)
        if m:
            base = S + '/' + m.group(1)
            if not os.path.exists(base + '.h'):  # case-insensitive include (util/SphereMap -> util/sphereMap)
                d, n = os.path.split(base)
                base = os.path.join(d, next(f[:-2] for f in os.listdir(d) if f.lower() == n.lower() + '.h'))
            main.append(open(base + '.h').read())
            glsl = open(base + '.glsl').read()
            extra.append(patch_glsl(glsl) if patch_glsl and base.endswith('puddles_common.frag') else glsl)
        else:
            main.append(line)
    return "\n".join(main), extra
def patch_puddles(code):
    anchor = "fragColor.a = mix(fragColor.a, 0.5+muddyPuddles*0.3, alphaPuddlesReflection);"
    at = code.index("void mainImage("); an = code.index(anchor, at)
    c = code[:an + len(anchor)] + "\n    pzGlint = pzHdrWaterGlint(normalize(gm), pzWindowPx()) * alphaPuddlesReflection;" + code[an + len(anchor):]
    c = c.replace("gl_FragColor = fragCol;", "gl_FragData[0] = fragCol;\n    gl_FragData[1] = vec4(pzGlint / (1.0 + pzGlint), pzSurfNow());")
    first = c.index("vec2 SphereMap(")
    return c[:first] + const('SURFACE_GLSL') + "\n" + c[first:]
for q in ('pzopt_puddles_hq', 'puddles_hq'):
    fm, fx = units(S + '/%s.frag' % q, patch_puddles)
    vm, vx = units(S + '/%s.vert' % q)
    files = []
    for i, t in enumerate([fm] + fx):
        open('/tmp/pz_%s_f%d.frag' % (q, i), 'w').write(t); files.append('/tmp/pz_%s_f%d.frag' % (q, i))
    open('/tmp/pz_%s.vert' % q, 'w').write(vm + '\n' + '\n'.join(re.sub(r'#version.*', '', x) for x in vx))
    # fragment units only: the game converts its #version 330 vertex sources itself (layout -> attribute), not mirrored here
    out = subprocess.run(['/tmp/glslcheck', '/tmp/pz_%s.vert' % q] + files, capture_output=True, text=True).stdout
    for line in out.splitlines():
        if '.frag: compile' in line:
            print(line)
            rc |= 'FAILED' in line
# vehicles (Hdr.patchVehicle): fragment compile of the patched main unit
def patch_vehicle(code):
    m = re.search(r'gl_FragColor\s*=\s*vec4\(\s*col\s*,\s*TexturePainColor\.a\s*\);', code)
    if not m:
        print('vehicle anchor missing'); return None
    anchor = m.group(0)
    j = java[java.index('static String patchVehicle'):]
    glsl_block = j[j.index('String glsl = String.join("\\n",'):j.index('String c = code.replace')]
    lines = [l.encode().decode('unicode_escape') for l in re.findall(r'"((?:[^"\\]|\\.)*)"', glsl_block)][1:]
    glsl = "\n".join(lines)
    c = code.replace(anchor, glsl + "gl_FragData[0] = vec4(col, TexturePainColor.a);").replace("gl_FragColor", "gl_FragData[0]")
    m = c.index("void main()")
    return c[:m] + "uniform vec4 pzHdrCar;\n" + c[m:]
for v in ('vehicle', 'vehicle_multiuv', 'vehicle_norandom_multiuv'):
    fm, fx = units(S + '/%s.frag' % v)
    fm = patch_vehicle(fm)
    if fm is None:
        rc = 1; continue
    files = []
    for i, t in enumerate([fm] + fx):
        open('/tmp/pz_%s_f%d.frag' % (v, i), 'w').write(t); files.append('/tmp/pz_%s_f%d.frag' % (v, i))
    out = subprocess.run(['/tmp/glslcheck', S + '/vehicle.vert'] + files, capture_output=True, text=True).stdout
    for line in out.splitlines():
        if '_f0.frag: compile' in line or 'error' in line:
            print(line)
            rc |= 'FAILED' in line
sys.exit(rc)
