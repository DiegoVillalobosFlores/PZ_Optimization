#!/usr/bin/env python3
"""Upload the staged Steam Workshop item straight through the Steamworks API: no game, no OCR.

  scripts/workshop-upload.py --notes "Release <commit> (game revision <rev>). ..."
  scripts/workshop-upload.py --check        # everything up to the submit: Steam logged on, fields, paths
  scripts/workshop-upload.py --notes "..." [--dir <staged item>] [--preview gif|png|<file>] [--timeout 600]

Loads the game's own natives/libsteam_api.so with SteamAppId=108600 and talks to the running,
logged-on Steam client (no password, no steamcmd login), then does what the in-game uploader
(zombie.core.znet.SteamWorkshop.SubmitWorkshopItem) does with the same inputs: StartItemUpdate on
the id in workshop.txt, title, description + "Workshop ID: <id>" + one "Mod ID: <id>" per mod.info
(SteamWorkshopItem.getSubmitDescription), visibility, tags, the Contents/ folder, the preview, and
SubmitItemUpdate with the change notes. The Steam client uploads in a few seconds; the call's own
SubmitItemUpdateResult_t is the verdict (EResult 1 = OK), workshop_log.txt is printed beside it.

The preview defaults to the animated preview.gif when it is staged and <= 1,000,000 bytes (the in-game
uploader could only send preview.png, which replaced the GIF on every release), else preview.png.
Stage first with scripts/workshop.sh (which also validates the Contents/ rules); `workshop.sh
--upload "<notes>"` runs both. Exit: 0 uploaded, 1 preflight / staging, 2 Steam API call refused,
3 the upload finished with an EResult other than OK or timed out.
"""
import argparse
import ctypes as C
import os
import re
import subprocess
import sys
import time
from pathlib import Path

APP_ID = 108600
HOME = Path.home()
STEAM_LOGS = HOME / ".local/share/Steam/logs"
PREVIEW_MAX = 1_000_000  # Steam's preview limit: a 1,011,209-byte GIF was refused (showcase-thumbnail-gif.py)
SUBMIT_RESULT = 3400 + 4   # k_iSteamUGCCallbacks + 4 = SubmitItemUpdateResult_t
VISIBILITY = {"public": 0, "friendsOnly": 1, "private": 2, "unlisted": 3}
STATUS = {0: "invalid", 1: "preparing config", 2: "preparing content", 3: "uploading content",
          4: "uploading preview", 5: "committing changes"}
ERESULT = {1: "OK", 2: "Fail", 3: "NoConnection", 8: "InvalidParam", 9: "FileNotFound", 14: "DuplicateName",
           15: "AccessDenied", 16: "Timeout", 17: "Banned", 20: "ServiceUnavailable", 21: "NotLoggedOn",
           24: "InsufficientPrivilege", 25: "LimitExceeded", 29: "DuplicateRequest", 33: "LockingFailed",
           34: "LogonSessionReplaced", 44: "ServiceReadOnly"}


def die(code, msg):
    print(msg, file=sys.stderr)
    sys.exit(code)


def read_workshop_txt(path):
    """SteamWorkshopItem.readWorkshopTxt: trimmed lines, description= lines joined with newlines,
    tags= split on ';' (Java's split: trailing empty strings dropped)."""
    item = {"id": "", "title": "", "description": [], "tags": [], "visibility": "public"}
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or line.startswith("//"):
            continue
        key = line.partition("=")[0]
        if key == "description":
            item["description"].append(line.replace("description=", ""))
        elif key == "tags":
            tags = line.replace("tags=", "").split(";")
            while tags and tags[-1] == "":
                tags.pop()
            item["tags"] += tags
        elif key in ("id", "title", "visibility"):
            item[key] = line.replace(key + "=", "")
    item["description"] = "\n".join(item["description"])
    return item


def mod_ids(contents):
    """The ids SteamWorkshopItem.validateModFolder collects: mod.info in each mods/<mod>/<version|common>/."""
    ids = []
    for info in sorted((contents / "mods").glob("*/*/mod.info")):
        for line in info.read_text(encoding="utf-8", errors="replace").splitlines():
            if line.startswith("id="):
                mid = line.replace("id=", "").strip()
                if mid and mid not in ids:
                    ids.append(mid)
                break
    return ids


def submit_description(item, ids):
    s = item["description"]
    if s:
        s += "\n\n"
    s += "Workshop ID: " + item["id"]
    for mid in ids:
        s += "\nMod ID: " + mid
    return s


def steam_logged_on():
    """The queue's check: a steam process and a last connection marker of Logged On. The client UI
    looks logged in after a Session Replaced, and every upload then fails (result=2)."""
    if subprocess.run(["pgrep", "-x", "steam"], capture_output=True).returncode != 0:
        return False, "no steam process"
    log = STEAM_LOGS / "connection_log.txt"
    if not log.exists():
        return False, f"no {log}"
    marks = re.findall(r"Logged On|Logged Off|Session Replaced", "\n".join(log.read_text(errors="replace").splitlines()[-200:]))
    if not marks or marks[-1] != "Logged On":
        return False, f"connection_log.txt ends in {marks[-1] if marks else 'no marker'!r}: steam -shutdown, start Steam, re-check"
    return True, "Logged On"


def workshop_log_lines():
    p = STEAM_LOGS / "workshop_log.txt"
    return p.read_text(errors="replace").splitlines() if p.exists() else []


class Steam:
    def __init__(self, lib):
        os.environ["SteamAppId"] = os.environ["SteamGameId"] = str(APP_ID)
        self.api = api = C.CDLL(str(lib))
        u64, vp, cp, b = C.c_uint64, C.c_void_p, C.c_char_p, C.c_bool
        sig = {
            "SteamAPI_InitFlat": (C.c_int, [C.c_char_p]),
            "SteamAPI_Shutdown": (None, []),
            "SteamAPI_RunCallbacks": (None, []),
            "SteamAPI_SteamUGC_v021": (vp, []),
            "SteamAPI_SteamUtils_v010": (vp, []),
            "SteamAPI_SteamUser_v023": (vp, []),
            "SteamAPI_SteamFriends_v018": (vp, []),
            "SteamAPI_ISteamUser_BLoggedOn": (b, [vp]),
            "SteamAPI_ISteamFriends_GetPersonaName": (cp, [vp]),
            "SteamAPI_ISteamUGC_StartItemUpdate": (u64, [vp, C.c_uint32, u64]),
            "SteamAPI_ISteamUGC_SetItemTitle": (b, [vp, u64, cp]),
            "SteamAPI_ISteamUGC_SetItemDescription": (b, [vp, u64, cp]),
            "SteamAPI_ISteamUGC_SetItemVisibility": (b, [vp, u64, C.c_int]),
            # the trailing bAllowAdminTags of newer SDKs; an extra register argument is ignored by older ones
            "SteamAPI_ISteamUGC_SetItemTags": (b, [vp, u64, vp, b]),
            "SteamAPI_ISteamUGC_SetItemContent": (b, [vp, u64, cp]),
            "SteamAPI_ISteamUGC_SetItemPreview": (b, [vp, u64, cp]),
            "SteamAPI_ISteamUGC_SubmitItemUpdate": (u64, [vp, u64, cp]),
            "SteamAPI_ISteamUGC_GetItemUpdateProgress": (C.c_int, [vp, u64, C.POINTER(u64), C.POINTER(u64)]),
            "SteamAPI_ISteamUtils_IsAPICallCompleted": (b, [vp, u64, C.POINTER(b)]),
            "SteamAPI_ISteamUtils_GetAPICallResult": (b, [vp, u64, vp, C.c_int, C.c_int, C.POINTER(b)]),
        }
        for name, (res, args) in sig.items():
            f = getattr(api, name)
            f.restype, f.argtypes = res, args
        err = C.create_string_buffer(1024)
        rc = api.SteamAPI_InitFlat(err)
        if rc != 0:
            die(1, f"SteamAPI_InitFlat failed ({rc}): {err.value.decode(errors='replace') or 'is Steam running and logged in?'}")
        self.ugc, self.utils = api.SteamAPI_SteamUGC_v021(), api.SteamAPI_SteamUtils_v010()
        self.user, self.friends = api.SteamAPI_SteamUser_v023(), api.SteamAPI_SteamFriends_v018()

    def logged_on(self):
        return self.api.SteamAPI_ISteamUser_BLoggedOn(self.user)

    def persona(self):
        return (self.api.SteamAPI_ISteamFriends_GetPersonaName(self.friends) or b"").decode(errors="replace")

    def shutdown(self):
        self.api.SteamAPI_Shutdown()


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--notes", help="change notes (required unless --check)")
    ap.add_argument("--dir", default=os.environ.get("ZOMBOID", str(HOME / "Zomboid")) + "/Workshop/PZ_Optimization")
    ap.add_argument("--preview", default="auto", help="auto (gif when staged and <= 1 MB, else png) | gif | png | <file>")
    ap.add_argument("--lib", default=os.environ.get("PZ_DIR", "/games/steamapps/common/ProjectZomboid/projectzomboid") + "/natives/libsteam_api.so")
    ap.add_argument("--timeout", type=float, default=600)
    ap.add_argument("--check", action="store_true", help="no submit: Steam session, fields and paths only")
    a = ap.parse_args()
    sys.stdout.reconfigure(line_buffering=True)  # the queue's `log -f` follows it live
    if not a.check and not a.notes:
        die(1, "--notes is required for an upload (or --check)")

    item_dir = Path(a.dir).expanduser()
    wtxt, contents = item_dir / "workshop.txt", item_dir / "Contents"
    if not wtxt.exists() or not contents.is_dir():
        die(1, f"{item_dir} is not staged (workshop.txt, Contents/): run scripts/workshop.sh first")
    item = read_workshop_txt(wtxt)
    if not item["id"].isdigit():
        die(1, f"{wtxt} has no numeric id=; the item is created once in the game, this script only updates it")
    ids = mod_ids(contents)
    if not ids:
        die(1, f"no mods/<mod>/<version>/mod.info with an id= under {contents}")
    if a.preview in ("auto", "gif", "png"):
        gif, png = item_dir / "preview.gif", item_dir / "preview.png"
        if a.preview == "gif" or (a.preview == "auto" and gif.exists() and gif.stat().st_size <= PREVIEW_MAX):
            preview = gif
        else:
            preview = png
    else:
        preview = Path(a.preview).expanduser()
    if not preview.exists():
        die(1, f"preview {preview} not found")
    if preview.stat().st_size > PREVIEW_MAX:
        die(1, f"preview {preview} is {preview.stat().st_size} bytes, Steam's limit is {PREVIEW_MAX}")
    if not Path(a.lib).exists():
        die(1, f"{a.lib} not found (set PZ_DIR or --lib)")
    desc = submit_description(item, ids)
    vis = VISIBILITY.get(item["visibility"], 0)

    ok, why = steam_logged_on()
    if not ok:
        die(1, f"Steam is not really logged on: {why}")
    t0 = time.time()
    steam = Steam(a.lib)
    try:
        if not steam.logged_on():
            die(1, "ISteamUser::BLoggedOn is false: the client has no live session (restart Steam)")
        print(f"steam: logged on as {steam.persona()!r}, app {APP_ID}")
        print(f"item {item['id']}: {item['title']!r}, {vis=} ({item['visibility']}), tags {item['tags']}, mod ids {ids}")
        print(f"description {len(desc)} chars, content {contents}, preview {preview.name} ({preview.stat().st_size} bytes)")
        ugc, api = steam.ugc, steam.api
        h = api.SteamAPI_ISteamUGC_StartItemUpdate(ugc, APP_ID, int(item["id"]))
        tags = (C.c_char_p * max(1, len(item["tags"])))(*[t.encode() for t in item["tags"]])

        class StringArray(C.Structure):  # SteamParamStringArray_t
            _fields_ = [("strings", C.POINTER(C.c_char_p)), ("count", C.c_int32)]
        tag_arr = StringArray(tags, len(item["tags"]))
        steps = [("title", api.SteamAPI_ISteamUGC_SetItemTitle(ugc, h, item["title"].encode())),
                 ("description", api.SteamAPI_ISteamUGC_SetItemDescription(ugc, h, desc.encode())),
                 ("visibility", api.SteamAPI_ISteamUGC_SetItemVisibility(ugc, h, vis)),
                 ("tags", api.SteamAPI_ISteamUGC_SetItemTags(ugc, h, C.addressof(tag_arr), False)),
                 ("content", api.SteamAPI_ISteamUGC_SetItemContent(ugc, h, str(contents.resolve()).encode())),
                 ("preview", api.SteamAPI_ISteamUGC_SetItemPreview(ugc, h, str(preview.resolve()).encode()))]
        refused = [n for n, r in steps if not r]
        if refused:
            die(2, f"Steam refused: {', '.join(refused)} (update handle {h})")
        if a.check:
            print(f"check OK: every field accepted ({time.time() - t0:.1f} s); nothing submitted")
            return 0
        log_start = len(workshop_log_lines())
        call = api.SteamAPI_ISteamUGC_SubmitItemUpdate(ugc, h, a.notes.encode())
        if not call:
            die(2, "SubmitItemUpdate returned no API call handle")
        print(f"submitted ({time.time() - t0:.1f} s); Steam is uploading")
        failed, last = C.c_bool(False), None
        done, total = C.c_uint64(0), C.c_uint64(0)
        deadline = time.time() + a.timeout
        while True:
            api.SteamAPI_RunCallbacks()
            if api.SteamAPI_ISteamUtils_IsAPICallCompleted(steam.utils, call, C.byref(failed)):
                break
            st = api.SteamAPI_ISteamUGC_GetItemUpdateProgress(ugc, h, C.byref(done), C.byref(total))
            if st != last:
                print(f"  {time.time() - t0:5.1f} s  {STATUS.get(st, st)}" + (f" ({done.value}/{total.value} bytes)" if total.value else ""))
                last = st
            if time.time() > deadline:
                die(3, f"no result after {a.timeout:.0f} s (last status: {STATUS.get(last, last)})")
            time.sleep(0.2)

        class SubmitResult(C.Structure):  # SubmitItemUpdateResult_t, callback pack 4 on Linux
            _pack_ = 4
            _fields_ = [("result", C.c_int), ("legal", C.c_bool), ("file_id", C.c_uint64)]
        res = SubmitResult()
        if failed.value or not api.SteamAPI_ISteamUtils_GetAPICallResult(steam.utils, call, C.addressof(res), C.sizeof(res), SUBMIT_RESULT, C.byref(failed)) or failed.value:
            die(3, "the SubmitItemUpdate call failed (IPC); check workshop_log.txt")
        tail = [l for l in workshop_log_lines()[log_start:] if item["id"] in l][-3:]
        print("workshop_log.txt:\n  " + ("\n  ".join(tail) if tail else "(no new lines for the item yet)"))
        if res.legal:
            print("note: Steam wants the Workshop legal agreement accepted on the item page before it goes public")
        name = ERESULT.get(res.result, "?")
        if res.result != 1:
            die(3, f"upload FAILED: EResult {res.result} ({name}) after {time.time() - t0:.1f} s"
                   + ("; a dead session (Session Replaced): restart Steam" if res.result in (2, 3, 21, 34) else ""))
        print(f"upload OK: EResult 1 (OK), item {res.file_id}, {time.time() - t0:.1f} s")
        return 0
    finally:
        steam.shutdown()


if __name__ == "__main__":
    sys.exit(main())
