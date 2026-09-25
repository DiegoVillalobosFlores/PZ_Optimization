#!/usr/bin/env python3
"""pzopt UI translations (issue #21): extract the English source and check the translation files.

The English text lives at the call sites and is the fallback (pzopt.I18n, src/lua/shared/pzopt/pzopt_i18n.lua):
  - Lua  PzoptT("key", "english" [.. "more"], ...) / T("key", ...)  in src/lua/**
  - Java I18n.text("key", "english", ...)                              in src/pzopt/**, src/overrides/**
  - the Optimizations / Enhancements / Profiler tables of pzopt_optimizations_options.lua: every entry
    { key = "k", label = "...", tip = "...", note = { v = "..." } } gives k.label, k.tip, k.note.<v>, every section
    title gives section.<first key>, and CLIP_TITLES / CLIP_NOTES / CLIP_SIDES / AXES / LEVELS give clip.<id>,
    clipNote.<id>, clipSide.<id>.1|2, axis.<id>[.tip], level.<word> (the loops after those tables).

  scripts/i18n.py            writes src/media/pzopt/translate/EN.json (the template for translators)
  scripts/i18n.py --check    fails when EN.json is stale, a key is used twice with different English, or a
                             translation's %N placeholders differ from the English; lists missing keys and warns
                             about keys EN lacks (renamed or removed). build.sh runs both.
"""
import json
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
OUT = REPO / "src/media/pzopt/translate"
OPTIONS = REPO / "src/lua/client/pzopt/pzopt_optimizations_options.lua"

LUA_STR = r'"((?:[^"\\]|\\.)*)"'
# a string literal, optionally continued with .. "more"
LUA_CONCAT = LUA_STR + r'(?:\s*\.\.\s*' + LUA_STR + r')*'
PLACEHOLDER = re.compile(r"%(\d)")


def unescape(s):
    return re.sub(r'\\(.)', lambda m: {"n": "\n", "t": "\t"}.get(m.group(1), m.group(1)), s)


def concat(text):
    """The value of `"a" .. "b" .. "c"`."""
    return "".join(unescape(m.group(1)) for m in re.finditer(LUA_STR, text))


class Keys:
    def __init__(self):
        self.en = {}
        self.errors = []

    def add(self, key, english, where):
        if key in self.en and self.en[key] != english:
            self.errors.append(f"{where}: {key} has two English texts:\n  {self.en[key]!r}\n  {english!r}")
        self.en.setdefault(key, english)


def calls(keys, text, where, fn):
    for m in re.finditer(fn + r'\(\s*"([^"]+)"\s*,\s*(' + LUA_CONCAT + r')', text):
        keys.add(m.group(1), concat(m.group(2)), where)


def block(text, name):
    """The source of `local NAME = { ... }` (brace matched, strings skipped)."""
    m = re.search(r"local " + name + r"\s*=\s*\{", text)
    if not m:
        raise SystemExit(f"i18n.py: table {name} not found in {OPTIONS.name}")
    i, depth = m.end() - 1, 0
    while True:
        c = text[i]
        if c == '"':
            i = re.compile(LUA_STR).match(text, i).end()
            continue
        if c == "-" and text.startswith("--", i):
            i = text.index("\n", i)
            continue
        depth += c == "{"
        depth -= c == "}"
        i += 1
        if depth == 0:
            return text[m.end():i - 1]


def field(entry, name):
    m = re.search(r"\b" + name + r"\s*=\s*(" + LUA_CONCAT + ")", entry)
    return concat(m.group(1)) if m else None


def notes(entry):
    m = re.search(r"\bnote\s*=\s*\{", entry)
    if not m:
        return {}
    body = entry[m.end():entry.index("}", m.end())]
    return {k1 or k2: unescape(v) for k1, k2, v in re.findall(r'(?:\["([^"]+)"\]|(\w+))\s*=\s*' + LUA_STR, body)}


def option_tables(keys, text):
    where = OPTIONS.name
    for name in ("SECTIONS", "ENHANCEMENT_SECTIONS", "PROFILER_SECTIONS"):
        src = block(text, name)
        # each section: title = "...", ... entries = { { key = ... }, ... }
        for sec in re.split(r"\n    \{\n", src)[1:]:
            title = field(sec, "title")
            entries = [e for e in re.split(r"\{\s*key\s*=\s*", sec)[1:]]
            first = re.match(LUA_STR, entries[0]).group(1)
            keys.add("section." + first, title, where)
            for e in entries:
                k = re.match(LUA_STR, e).group(1)
                entry_keys(keys, k, e, where)
    master = block_expr(text, "MASTER")
    entry_keys(keys, "enabled", master, where)
    for m in re.finditer(r"(\w+)\s*=\s*(" + LUA_CONCAT + ")", block(text, "CLIP_TITLES")):
        keys.add("clip." + m.group(1), concat(m.group(2)), where)
    for m in re.finditer(r"(\w+)\s*=\s*" + LUA_STR, block(text, "CLIP_NOTES")):
        keys.add("clipNote." + m.group(1), unescape(m.group(2)), where)
    for m in re.finditer(r"(\w+)\s*=\s*\{\s*" + LUA_STR + r"\s*,\s*" + LUA_STR + r"\s*\}", block(text, "CLIP_SIDES")):
        keys.add(f"clipSide.{m.group(1)}.1", unescape(m.group(2)), where)
        keys.add(f"clipSide.{m.group(1)}.2", unescape(m.group(3)), where)
    for e in re.split(r"\{\s*id\s*=\s*", block(text, "AXES"))[1:]:
        axis = re.match(LUA_STR, e).group(1)
        keys.add("axis." + axis, field(e, "label"), where)
        tip = field(e, "tip")
        if tip:
            keys.add(f"axis.{axis}.tip", tip, where)
    for word in re.findall(r"=\s*" + LUA_STR, block(text, "LEVELS")):
        keys.add("level." + word, word, where)


def block_expr(text, name):
    m = re.search(r"local " + name + r"\s*=\s*\{(.*?)\}\n", text, re.S)
    return m.group(1)


def entry_keys(keys, k, e, where):
    keys.add(k + ".label", field(e, "label"), where)
    keys.add(k + ".tip", field(e, "tip"), where)
    for v, text in notes(e).items():
        keys.add(f"{k}.note.{v}", text, where)


def extract():
    keys = Keys()
    for f in sorted((REPO / "src/lua").rglob("*.lua")):
        text = f.read_text(encoding="utf-8")
        calls(keys, text, f.name, r"\b(?:PzoptT|T)")
        if f == OPTIONS:
            option_tables(keys, text)
    for root in ("src/pzopt", "src/overrides"):
        for f in sorted((REPO / root).rglob("*.java")):
            text = f.read_text(encoding="utf-8")
            if "I18n.text" in text:
                calls(keys, text, f.name, r"I18n\.text")
    return keys


def dump(d):
    return json.dumps(dict(sorted(d.items())), ensure_ascii=False, indent=4) + "\n"


def main():
    keys = extract()
    for e in keys.errors:
        print("ERROR", e)
    en_file = OUT / "EN.json"
    if "--check" not in sys.argv:
        OUT.mkdir(parents=True, exist_ok=True)
        en_file.write_text(dump(keys.en), encoding="utf-8", newline="\n")
        print(f"i18n: {len(keys.en)} keys -> {en_file.relative_to(REPO)}")
        return 1 if keys.errors else 0
    bad = bool(keys.errors)
    if not en_file.exists() or en_file.read_text(encoding="utf-8") != dump(keys.en):
        print("ERROR EN.json is stale: run scripts/i18n.py")
        bad = True
    for f in sorted(OUT.glob("*.json")):
        if f.name == "EN.json":
            continue
        tr = json.loads(f.read_text(encoding="utf-8"))
        extra = sorted(set(tr) - set(keys.en))
        missing = sorted(set(keys.en) - set(tr))
        for k in extra:
            print(f"WARN {f.name}: {k} is not an English key (renamed or removed?)")
        for k in sorted(set(tr) & set(keys.en)):
            if sorted(PLACEHOLDER.findall(tr[k])) != sorted(PLACEHOLDER.findall(keys.en[k])):
                print(f"ERROR {f.name}: {k} placeholders differ from the English")
                bad = True
        print(f"i18n: {f.name} {len(keys.en) - len(missing)}/{len(keys.en)} translated"
              + (f", missing: {', '.join(missing[:20])}{' ...' if len(missing) > 20 else ''}" if missing else ""))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
