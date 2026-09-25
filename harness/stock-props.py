#!/usr/bin/env python3
"""Print the run.sh --prop arguments of a "stock game" run that keeps the overlay and the profiler on.

`--prop enabled=false` is the real stock path, and since 2026-09-24 it keeps the in-game overlay and the game-thread
profiler (Overlay.ACTIVE = Overrides.buildMatches(); before, it switched them off and every recorded comparison has to
show the profiler on every panel). This script is the other stock side: the overrides active, every optimization key
turned to its stock value, one by one. The
list used to be kept by hand (72 keys on 2026-09-22) and went stale with every new key; this derives it from the code:

* every boolean key of src/pzopt/pzopt/Config.java -> false, except the keys in KEEP (master switch, harness,
  overlay / profiler, multiplayer-only) and the dev*/debug keys, which are left at their defaults;
* every numeric / choice key the Options tab marks with a "stock" or "vanilla" note -> that value;
* the numeric keys in STOCK below (stock values read from their Config comments; the rest are inert once their
  feature's boolean is off, or only change load-time thread counts).

    harness/run.sh ... $(python3 harness/stock-props.py)          # bash
    stock=(${(f)"$(python3 harness/stock-props.py --lines)"})      # zsh: one element per line
    python3 harness/stock-props.py --check                         # print what it decided and why

Check the console's `[pzopt] settings:` line of the stock run.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CONFIG = ROOT / "src/pzopt/pzopt/Config.java"
OPTIONS = ROOT / "src/lua/client/pzopt/pzopt_optimizations_options.lua"

# Booleans left at their defaults: not optimizations, or needed for the recording.
KEEP = {
    "enabled", "instrument", "luaChecksumExempt", "updateCheck", "updateFromWorkshop", "updatePrefetch", "profileHandshake",
    "overlay", "overlaySampling", "overlayLog", "overlayTexture", "overlayFpsColor", "overlayFpsFollowCap",
    "persistentVboCoherent",  # a sub-key of persistentVbo, inert with it off
    "dumpItems",
}
# Numeric / text keys whose stock value is in their Config comment rather than in an Options-tab note.
STOCK = {
    "curtainDepthNudgePct": "0",
    "lightingRebakeBudget": "0",
    "lightingStrongBudget": "0",
    "jitMode": "tiered",
    "renderChunkPrewarm": "0",
    "upscaler": "off",
    "chunkGridWidth": "19",
}
# Always, on both sides of a showcase comparison.
ALWAYS = {"uncappedFps": "true", "overlay": "true", "overlayFont": "Large"}


def config_keys():
    text = CONFIG.read_text()
    keys = {}
    for m in re.finditer(r'\b(bool|integer|string)\("([A-Za-z0-9]+)",', text):
        keys.setdefault(m.group(2), m.group(1))
    return keys


def tab_stock_notes():
    text = OPTIONS.read_text()
    notes = {}
    for m in re.finditer(r'\{\s*key = "([A-Za-z0-9]+)"(.*?)tip = ', text, re.S):
        n = re.search(r'note = \{(.*?)\}', m.group(2), re.S)
        if not n:
            continue
        for value, label in re.findall(r'\["([^"]+)"\] = "([^"]*)"', n.group(1)):
            if "stock" in label.lower() or "vanilla" in label.lower():
                notes[m.group(1)] = value
    return notes


def stock_props():
    keys, notes = config_keys(), tab_stock_notes()
    props, why = {}, {}
    for key, kind in keys.items():
        if kind == "bool":
            if key in KEEP or key.startswith("dev"):
                continue
            props[key], why[key] = "false", "boolean optimization"
        elif key in STOCK:
            props[key], why[key] = STOCK[key], "Config comment"
        elif key in notes:
            props[key], why[key] = notes[key], "Options-tab stock note"
    for key, value in ALWAYS.items():
        props[key], why[key] = value, "recording"
    return props, why


def main():
    props, why = stock_props()
    if "--check" in sys.argv:
        for key in sorted(props):
            print(f"{key}={props[key]:10} {why[key]}")
        print(f"{len(props)} props", file=sys.stderr)
        return
    args = []
    for key in sorted(props):
        args += ["--prop", f"{key}={props[key]}"]
    print("\n".join(args) if "--lines" in sys.argv else " ".join(args))


if __name__ == "__main__":
    main()
