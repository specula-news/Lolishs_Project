#!/usr/bin/env python3
"""
Sample servers for the screenshot job (tools/screenshots/shoot.sh).

  :8096  a tiny Jellyfin look-alike with made-up movies and shows
  :5000  a tiny Li'Server look-alike (search, downloads, trending)

Every title, poster and backdrop is invented and drawn here with Pillow, so the screenshots
show the real app without needing the real servers or any passwords.
"""
import hashlib
import io
import json
import math
import random
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

from PIL import Image, ImageDraw, ImageFilter, ImageFont

HOST_FROM_EMULATOR = "http://10.0.2.2:8096"
USER_ID = "u-lolish"
TICKS = 10_000_000

# --- Sample library -----------------------------------------------------------------------------

MOVIES = [
    ("m1", "Northern Lights", 2024, 8.1, "PG-13", ["Drama", "Adventure"], 128,
     "A lighthouse keeper on a remote island finds a radio signal that should not exist, and follows it north into the polar night."),
    ("m2", "Glass City", 2025, 7.6, "R", ["Thriller", "Sci-Fi"], 117,
     "In a city where every wall is transparent, a detective has to solve the one crime nobody saw."),
    ("m3", "The Last Harbor", 2023, 7.9, "PG-13", ["Drama"], 134,
     "Two estranged sisters return to the fishing town they grew up in for one final summer season."),
    ("m4", "Ember Road", 2025, 6.9, "PG-13", ["Action", "Adventure"], 109,
     "A courier crosses a desert on fire to deliver a package that could end a war."),
    ("m5", "Paper Moons", 2022, 7.2, "PG", ["Animation", "Family"], 96,
     "A girl who folds paper animals discovers they come alive at night."),
    ("m6", "Quiet Frequency", 2024, 7.4, "R", ["Mystery", "Horror"], 101,
     "A late-night radio host starts receiving calls from listeners who died years ago."),
    ("m7", "Silver Tide", 2021, 6.8, "PG-13", ["Romance", "Drama"], 112,
     "A marine biologist and a sailor keep meeting at the same harbor, a year apart each time."),
    ("m8", "Orbit Nine", 2025, 8.3, "PG-13", ["Sci-Fi", "Adventure"], 141,
     "The crew of a mining ship wakes up nine years late, in an orbit that no map shows."),
    ("m9", "Wildflower", 2023, 7.0, "PG", ["Comedy", "Drama"], 99,
     "A retired chef opens a restaurant in the middle of nowhere and accidentally becomes famous."),
    ("m10", "Midnight Atlas", 2024, 7.7, "R", ["Crime", "Thriller"], 123,
     "A mapmaker is hired to draw a city that the government says does not exist."),
    ("m11", "Copper Sky", 2022, 6.6, "PG-13", ["Western"], 115,
     "A small mining town hires a stranger to stand up to the company that owns everything."),
    ("m12", "The Lantern Garden", 2025, 7.8, "PG", ["Fantasy", "Family"], 104,
     "Every lantern in grandmother's garden holds a memory, and one of them has gone out."),
]

SERIES = [
    ("s1", "Frostline", 2023, 8.4, "TV-MA", ["Drama", "Thriller"], 2, 8,
     "A border town in the far north hides a secret beneath the ice, and the new sheriff wants it out."),
    ("s2", "Neon Harbor", 2024, 7.9, "TV-14", ["Crime", "Drama"], 2, 10,
     "Dockworkers, smugglers and one honest cop in a port city that never sleeps."),
    ("s3", "The Cartographers", 2022, 8.0, "TV-14", ["Mystery", "Adventure"], 3, 8,
     "A family of mapmakers discovers that the places they draw begin to change."),
    ("s4", "Starfall Academy", 2025, 7.5, "TV-PG", ["Sci-Fi", "Family"], 1, 10,
     "Teenagers at a school on the Moon learn the real reason it was built."),
    ("s5", "Kitchen Wars", 2024, 7.1, "TV-PG", ["Reality", "Comedy"], 3, 12,
     "Twelve home cooks, one old restaurant, and a very honest grandmother as the judge."),
    ("s6", "Hollow Pines", 2023, 7.7, "TV-MA", ["Horror", "Mystery"], 2, 6,
     "Campers at a lakeside resort start losing the same hour every night."),
]

EPISODE_NAMES = ["Pilot", "The Thaw", "Signals", "Undertow", "Crossing", "Night Shift", "Old Debts", "Whiteout",
                 "Low Tide", "The Map Room", "Ashes", "Homecoming"]

PALETTES = [
    ((14, 24, 54), (66, 120, 186), (255, 196, 110)),
    ((26, 10, 40), (126, 48, 138), (255, 120, 150)),
    ((8, 32, 36), (30, 120, 120), (230, 240, 200)),
    ((40, 14, 10), (190, 70, 40), (255, 210, 120)),
    ((10, 12, 20), (60, 70, 110), (200, 220, 255)),
    ((22, 30, 14), (98, 140, 60), (250, 230, 160)),
    ((30, 18, 46), (88, 72, 168), (120, 230, 220)),
    ((44, 20, 24), (170, 60, 80), (255, 190, 170)),
]


def h(text):
    return int(hashlib.md5(text.encode()).hexdigest(), 16)


def iso(days):
    return time.strftime("%Y-%m-%dT%H:%M:%S.0000000Z", time.gmtime(time.time() + days * 86400))


def base_item(item_id, name, kind, year, rating, official, genres, overview):
    return {
        "Id": item_id, "Name": name, "Type": kind, "ProductionYear": year,
        "CommunityRating": rating, "OfficialRating": official, "Genres": genres, "Overview": overview,
        "ImageTags": {"Primary": "p" + item_id, "Thumb": "t" + item_id},
        "BackdropImageTags": ["b" + item_id],
        "UserData": {"PlaybackPositionTicks": 0, "Played": False, "IsFavorite": h(item_id) % 3 == 0,
                     "PlayedPercentage": 0},
        "PremiereDate": f"{year}-03-14T00:00:00.0000000Z",
        "Studios": [{"Name": "Aurora Pictures"}],
        "People": [],
    }


ITEMS = {}
for mid, name, year, rating, official, genres, minutes, overview in MOVIES:
    it = base_item(mid, name, "Movie", year, rating, official, genres, overview)
    it["RunTimeTicks"] = minutes * 60 * TICKS
    it["Taglines"] = ["Some signals are meant to be followed."] if mid == "m1" else []
    it["MediaSources"] = [{"MediaStreams": [
        {"Type": "Video", "DisplayTitle": "4K HEVC HDR10"},
        {"Type": "Audio", "DisplayTitle": "English - Dolby TrueHD 7.1 Atmos", "IsDefault": True},
        {"Type": "Subtitle"}, {"Type": "Subtitle"}, {"Type": "Subtitle"}]}]
    ITEMS[mid] = it

SEASONS = {}
EPISODES = {}
for sid, name, year, rating, official, genres, seasons, per_season, overview in SERIES:
    it = base_item(sid, name, "Series", year, rating, official, genres, overview)
    it["ChildCount"] = seasons
    it["Status"] = "Continuing"
    it["RunTimeTicks"] = 52 * 60 * TICKS
    it["UserData"]["UnplayedItemCount"] = per_season
    ITEMS[sid] = it
    SEASONS[sid] = []
    for s in range(1, seasons + 1):
        season_id = f"{sid}-s{s}"
        season = {"Id": season_id, "Name": f"Season {s}", "Type": "Season", "IndexNumber": s, "SeriesId": sid,
                  "SeriesName": name, "ImageTags": {"Primary": "p" + sid}, "UserData": {"UnplayedItemCount": per_season}}
        ITEMS[season_id] = season
        SEASONS[sid].append(season)
        EPISODES[season_id] = []
        for e in range(1, per_season + 1):
            eid = f"{sid}-s{s}e{e}"
            ep = {
                "Id": eid, "Name": EPISODE_NAMES[(e - 1) % len(EPISODE_NAMES)], "Type": "Episode",
                "IndexNumber": e, "ParentIndexNumber": s, "SeriesId": sid, "SeriesName": name, "SeasonId": season_id,
                "ImageTags": {"Primary": "p" + eid}, "SeriesPrimaryImageTag": "p" + sid,
                "ParentBackdropItemId": sid, "ParentBackdropImageTags": ["b" + sid],
                "ParentThumbItemId": sid, "ParentThumbImageTag": "t" + sid,
                "RunTimeTicks": (44 + (e * 7) % 15) * 60 * TICKS, "CommunityRating": round(rating - 0.4 + (e % 5) * 0.2, 1),
                "OfficialRating": official, "ProductionYear": year + s - 1,
                "PremiereDate": f"{year + s - 1}-0{1 + e % 9}-1{e % 9}T00:00:00.0000000Z",
                "Overview": f"{name} continues: the stakes rise as old allies turn and the truth gets closer.",
                "UserData": {"PlaybackPositionTicks": 0, "Played": s == 1 and e < 4, "IsFavorite": False},
                "MediaSources": [{"MediaStreams": [{"Type": "Video", "DisplayTitle": "1080p HEVC"},
                                                   {"Type": "Audio", "DisplayTitle": "English - EAC3 5.1", "IsDefault": True},
                                                   {"Type": "Subtitle"}]}],
            }
            ITEMS[eid] = ep
            EPISODES[season_id].append(ep)


def with_progress(item, percent):
    it = dict(item)
    it["UserData"] = dict(item["UserData"], PlaybackPositionTicks=int(item.get("RunTimeTicks", 0) * percent / 100),
                          PlayedPercentage=percent)
    return it


RESUME = [with_progress(ITEMS["m1"], 46), with_progress(ITEMS["s1-s2e3"], 22), with_progress(ITEMS["m8"], 71),
          with_progress(ITEMS["s3-s1e5"], 58), with_progress(ITEMS["m6"], 15)]
NEXT_UP = [ITEMS["s2-s1e4"], ITEMS["s4-s1e2"], ITEMS["s6-s2e1"], ITEMS["s5-s3e7"], ITEMS["s1-s2e4"]]
UPCOMING = []
for i, eid in enumerate(["s1-s2e7", "s2-s2e9", "s4-s1e9", "s6-s2e5", "s3-s3e7", "s5-s3e11"]):
    ep = dict(ITEMS[eid])
    ep["PremiereDate"] = iso(1 + i * 2)
    UPCOMING.append(ep)

VIEWS = [
    {"Id": "lib-movies", "Name": "Movies", "Type": "CollectionFolder", "CollectionType": "movies",
     "ImageTags": {"Primary": "plib-movies"}},
    {"Id": "lib-shows", "Name": "Shows", "Type": "CollectionFolder", "CollectionType": "tvshows",
     "ImageTags": {"Primary": "plib-shows"}},
]

MOVIE_LIST = [ITEMS[m[0]] for m in MOVIES]
SERIES_LIST = [ITEMS[s[0]] for s in SERIES]

# --- Artwork ---------------------------------------------------------------------------------------

FONT_CACHE = {}


def font(size, bold=True):
    key = (size, bold)
    if key not in FONT_CACHE:
        names = (["DejaVuSans-Bold.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"] if bold
                 else ["DejaVuSans.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"])
        f = None
        for n in names:
            try:
                f = ImageFont.truetype(n, size)
                break
            except OSError:
                pass
        FONT_CACHE[key] = f or ImageFont.load_default(size=size)
    return FONT_CACHE[key]


def gradient(w, hgt, top, bottom):
    img = Image.new("RGB", (w, hgt))
    d = ImageDraw.Draw(img)
    for y in range(hgt):
        t = y / max(1, hgt - 1)
        d.line([(0, y), (w, y)], fill=tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3)))
    return img


def scene(w, hgt, seed_text):
    seed = h(seed_text)
    rnd = random.Random(seed)
    dark, mid, light = PALETTES[seed % len(PALETTES)]
    img = gradient(w, hgt, dark, mid)
    # Glowing sun / moon
    glow = Image.new("RGBA", (w, hgt), (0, 0, 0, 0))
    gd = ImageDraw.Draw(glow)
    cx, cy = rnd.uniform(0.2, 0.8) * w, rnd.uniform(0.18, 0.45) * hgt
    r = min(w, hgt) * rnd.uniform(0.10, 0.18)
    gd.ellipse([cx - r * 2.4, cy - r * 2.4, cx + r * 2.4, cy + r * 2.4], fill=light + (70,))
    glow = glow.filter(ImageFilter.GaussianBlur(r * 0.9))
    img.paste(glow, (0, 0), glow)
    d = ImageDraw.Draw(img)
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=light)
    # Stars
    for _ in range(int(w * hgt / 9000)):
        x, y = rnd.uniform(0, w), rnd.uniform(0, hgt * 0.5)
        s = rnd.choice([1, 1, 1, 2])
        d.ellipse([x, y, x + s, y + s], fill=(255, 255, 255))
    # Mountain / skyline layers, darker towards the front
    style = seed % 3
    for layer in range(4):
        t = layer / 3
        col = tuple(int(mid[i] * (1 - t) * 0.75 + dark[i] * t * 0.6) for i in range(3))
        base_y = hgt * (0.55 + layer * 0.11)
        pts = [(0, hgt)]
        if style == 1:  # city
            x = 0
            while x < w:
                bw = rnd.uniform(0.04, 0.10) * w
                bh = rnd.uniform(0.05, 0.25) * hgt * (1.2 - t * 0.5)
                pts += [(x, base_y), (x, base_y - bh), (x + bw, base_y - bh), (x + bw, base_y)]
                x += bw
        else:  # mountains / waves
            steps = 7 if style == 0 else 24
            amp = (0.16 if style == 0 else 0.03) * hgt
            for i in range(steps + 1):
                x = w * i / steps
                y = base_y - (rnd.uniform(0.2, 1.0) * amp if style == 0 else math.sin(i * 0.9 + layer) * amp)
                pts.append((x, y))
        pts.append((w, hgt))
        d.polygon(pts, fill=col)
    return img


def shade_bottom(img, strength=0.85):
    w, hgt = img.size
    overlay = gradient(w, hgt, (0, 0, 0), (0, 0, 0))
    mask = Image.new("L", (w, hgt))
    md = ImageDraw.Draw(mask)
    for y in range(hgt):
        t = max(0.0, (y / hgt - 0.5) / 0.5)
        md.line([(0, y), (w, y)], fill=int(255 * strength * t))
    img.paste(overlay, (0, 0), mask)
    return img


def wrap(text, f, width, draw):
    words, lines, cur = text.split(), [], ""
    for wd in words:
        test = (cur + " " + wd).strip()
        if draw.textlength(test, font=f) <= width:
            cur = test
        else:
            lines.append(cur)
            cur = wd
    if cur:
        lines.append(cur)
    return lines


def poster(item_id):
    it = ITEMS.get(item_id, {"Name": item_id, "ProductionYear": ""})
    w, hgt = 400, 600
    img = shade_bottom(scene(w, hgt, item_id))
    d = ImageDraw.Draw(img)
    title = it.get("Name", "").upper()
    size = 44
    while True:  # shrink until the longest word fits
        f = font(size)
        lines = wrap(title, f, w - 50, d)
        if size <= 22 or max(d.textlength(line, font=f) for line in lines) <= w - 40:
            break
        size -= 2
    y = hgt - 70 - len(lines) * 50
    for line in lines:
        d.text(((w - d.textlength(line, font=f)) / 2, y), line, font=f, fill=(255, 255, 255))
        y += 50
    small = font(16, bold=False)
    tag = "A FILM BY AURORA PICTURES" if it.get("Type") == "Movie" else "AN ORIGINAL SERIES"
    d.text(((w - d.textlength(tag, font=small)) / 2, hgt - 46), tag, font=small, fill=(230, 230, 230))
    return img


def landscape(item_id, w=1280, hgt=720, title=False):
    img = scene(w, hgt, item_id + "-wide")
    if title:
        it = ITEMS.get(item_id, {})
        img = shade_bottom(img, 0.6)
        d = ImageDraw.Draw(img)
        f = font(int(hgt * 0.09))
        d.text((int(w * 0.05), int(hgt * 0.78)), it.get("Name", "").upper(), font=f, fill=(255, 255, 255))
    return img


def avatar():
    img = gradient(240, 240, (255, 140, 90), (160, 60, 200))
    d = ImageDraw.Draw(img)
    f = font(120)
    d.text(((240 - d.textlength("L", font=f)) / 2, 40), "L", font=f, fill=(255, 255, 255))
    return img


IMAGE_CACHE = {}


def image_bytes(key, make, max_width):
    ck = (key, max_width)
    if ck not in IMAGE_CACHE:
        img = make()
        if max_width and img.width > max_width:
            img = img.resize((max_width, int(img.height * max_width / img.width)), Image.LANCZOS)
        buf = io.BytesIO()
        img.save(buf, "JPEG", quality=88)
        IMAGE_CACHE[ck] = buf.getvalue()
    return IMAGE_CACHE[ck]


# --- Jellyfin -------------------------------------------------------------------------------------

def items_query(q):
    parent = q.get("parentId", [None])[0]
    types = (q.get("includeItemTypes", [""])[0] or "").split(",")
    filters = q.get("filters", [""])[0] or ""
    term = (q.get("searchTerm", [""])[0] or "").lower()
    if parent == "lib-movies" or types == ["Movie"]:
        pool = MOVIE_LIST
    elif parent == "lib-shows" or types == ["Series"]:
        pool = SERIES_LIST
    else:
        pool = MOVIE_LIST + SERIES_LIST
    if "IsFavorite" in filters:
        pool = [i for i in pool if i["UserData"]["IsFavorite"]]
    if term:
        pool = [i for i in pool if term in i["Name"].lower()]
    sort = q.get("sortBy", ["SortName"])[0]
    if "Rating" in sort:
        pool = sorted(pool, key=lambda i: -i.get("CommunityRating", 0))
    elif "DateCreated" in sort or "Premiere" in sort:
        pool = sorted(pool, key=lambda i: -i.get("ProductionYear", 0))
    else:
        pool = sorted(pool, key=lambda i: i["Name"])
    start = int(q.get("startIndex", ["0"])[0])
    limit = int(q.get("limit", ["100"])[0])
    return {"Items": pool[start:start + limit], "TotalRecordCount": len(pool)}


def jellyfin(path, q):
    parts = [p for p in path.split("/") if p]
    if path == "/System/Info/Public":
        return {"ServerName": "Lolish", "Version": "10.11.0", "Id": "demo"}
    if path == "/Users/Public":
        return [{"Id": USER_ID, "Name": "Lolish", "HasPassword": True, "PrimaryImageTag": "avatar"}]
    if path == "/Users/Me":
        return {"Id": USER_ID, "Name": "Lolish", "PrimaryImageTag": "avatar"}
    if path == "/UserViews":
        return {"Items": VIEWS, "TotalRecordCount": len(VIEWS)}
    if path == "/UserItems/Resume":
        return {"Items": RESUME, "TotalRecordCount": len(RESUME)}
    if path == "/Shows/NextUp":
        return {"Items": NEXT_UP, "TotalRecordCount": len(NEXT_UP)}
    if path == "/Shows/Upcoming":
        return {"Items": UPCOMING, "TotalRecordCount": len(UPCOMING)}
    if path == "/Items/Latest":
        parent = q.get("parentId", [""])[0]
        pool = SERIES_LIST if parent == "lib-shows" else MOVIE_LIST
        return list(reversed(pool))
    if path == "/Items":
        return items_query(q)
    if len(parts) == 3 and parts[0] == "Shows" and parts[2] == "Seasons":
        s = SEASONS.get(parts[1], [])
        return {"Items": s, "TotalRecordCount": len(s)}
    if len(parts) == 3 and parts[0] == "Shows" and parts[2] == "Episodes":
        season = q.get("seasonId", [None])[0] or (SEASONS.get(parts[1]) or [{"Id": ""}])[0]["Id"]
        eps = EPISODES.get(season, [])
        return {"Items": eps, "TotalRecordCount": len(eps)}
    if len(parts) == 3 and parts[0] == "Items" and parts[2] == "Similar":
        pool = [i for i in (MOVIE_LIST + SERIES_LIST) if i["Id"] != parts[1]][:10]
        return {"Items": pool, "TotalRecordCount": len(pool)}
    if len(parts) == 2 and parts[0] == "Items":
        it = ITEMS.get(parts[1])
        if it:
            it = dict(it)
            it["People"] = [{"Id": f"p{n}", "Name": nm, "Role": rl, "Type": "Actor"} for n, (nm, rl) in enumerate([
                ("Mara Lind", "Ingrid"), ("Jonah Vale", "Tomas"), ("Elsa Brandt", "Captain Holm"),
                ("Ravi Sen", "Dr. Okafor"), ("Nora Quist", "Lena")])]
            return it
    if parts[:1] == ["MediaSegments"]:
        return {"Items": []}
    return {"Items": [], "TotalRecordCount": 0}


def jellyfin_image(path, q):
    parts = [p for p in path.split("/") if p]
    max_width = int(q.get("maxWidth", ["0"])[0] or 0)
    if parts[0] == "Users":
        return image_bytes("avatar", avatar, max_width)
    if parts[0] == "Items" and len(parts) >= 4:
        item_id, kind = parts[1], parts[3]
        it = ITEMS.get(item_id, {})
        if kind == "Primary" and it.get("Type") in ("Episode",):
            return image_bytes(("ep", item_id), lambda: landscape(item_id, 640, 360), max_width)
        if kind == "Primary":
            sid = it.get("SeriesId") if it.get("Type") == "Season" else None
            return image_bytes(("poster", sid or item_id), lambda: poster(sid or item_id), max_width)
        if kind == "Thumb":
            return image_bytes(("thumb", item_id), lambda: landscape(item_id, 960, 540, title=True), max_width)
        return image_bytes(("backdrop", item_id), lambda: landscape(item_id), max_width)
    return None


# --- Li'Server ------------------------------------------------------------------------------------

def magnet(name):
    return "magnet:?xt=urn:btih:" + hashlib.sha1(name.encode()).hexdigest()


def li_search(query, mode):
    import re
    words = [w for w in query.split() if w]
    code = next((w.upper() for w in words if re.fullmatch(r"[sS]\d{1,2}([eE]\d{1,2})?", w)), "S01")
    skip = ("1080", "720", "2160", "x26", "hevc", "elite", "megusta", "bone")
    title = ".".join(w.capitalize() for w in words if not w.lower().startswith(skip) and w.upper() != code)
    title = title or "Northern.Lights"
    now = int(time.time())
    variants = [
        ("S01.1080p.WEB-DL.x265.10bit-ELiTE", 412, "4.1 GB", 2, "1337x"),
        ("S01.1080p.WEB.H264-MeGusta", 268, "9.8 GB", 3, "TorrentGalaxy"),
        ("S01.2160p.WEB-DL.DV.HDR.HEVC-BONE", 96, "38.2 GB", 5, "1337x"),
        ("S01.720p.WEB.x265-MiNX", 151, "2.3 GB", 9, "EZTV"),
        ("S01.1080p.BluRay.x265-RARBG", 74, "7.4 GB", 210, "The Pirate Bay"),
        ("S01E01.1080p.WEB.x265-ELiTE", 38, "540 MB", 1, "1337x"),
        ("S01.COMPLETE.480p.WEB.x264", 3, "1.9 GB", 400, "YTS"),
        ("S01.1080p.AMZN.WEB-DL.DDP5.1.H.264", 0, "11.6 GB", 30, "Nyaa"),
    ]
    if mode == "private":
        variants.insert(1, ("S01.1080p.WEB-DL.x265-TD", 87, "4.4 GB", 1, "TorrentDay"))
    out = []
    for suffix, seeds, size, age_days, tracker in variants:
        name = f"{title}.{suffix.replace('S01', code, 1)}"
        out.append({"name": name, "magnet": magnet(name), "size_str": size, "seeds": seeds,
                    "date": now - age_days * 86400, "tracker": tracker})
    return out


START = time.time()


def li_downloads():
    t = time.time() - START
    wobble = lambda base: f"{base + math.sin(t / 3) * 0.6:.1f} MB/s"
    return {"all": [
        {"hash": "a1", "name": "Frostline.S02.1080p.WEB-DL.x265-ELiTE", "state": "Downloading",
         "progress": min(99.0, 42.5 + t * 0.2), "speed": wobble(8.4), "upload_speed": "1.2 MB/s",
         "total_size_str": "6.8 GB", "waiting": False},
        {"hash": "a2", "name": "Orbit.Nine.2025.2160p.WEB-DL.DV.HEVC-BONE", "state": "Downloading",
         "progress": 77.0, "speed": wobble(5.1), "upload_speed": "640 KB/s", "total_size_str": "21.4 GB", "waiting": False},
        {"hash": "a3", "name": "Neon.Harbor.S02E09.1080p.WEB.x265-MeGusta", "state": "Queued", "progress": 0.0,
         "speed": "0 B/s", "upload_speed": "0 B/s", "total_size_str": "780 MB", "waiting": True},
        {"hash": "a4", "name": "Hollow.Pines.S02.1080p.WEB.H264-NTb", "state": "Paused", "progress": 12.0,
         "speed": "0 B/s", "upload_speed": "0 B/s", "total_size_str": "9.2 GB", "waiting": False},
        {"hash": "b1", "name": "The.Last.Harbor.2023.1080p.BluRay.x265-RARBG", "state": "Seeding", "progress": 100.0,
         "speed": "0 B/s", "upload_speed": "2.4 MB/s", "total_size_str": "7.1 GB", "waiting": False,
         "seed": {"h": 5.2, "goal": 24}, "is_private": True},
        {"hash": "b2", "name": "Starfall.Academy.S01.1080p.WEB-DL.x265-ELiTE", "state": "Seeding", "progress": 100.0,
         "speed": "0 B/s", "upload_speed": "380 KB/s", "total_size_str": "5.6 GB", "waiting": False,
         "seed": {"h": 19.5, "goal": 24}},
    ]}


def li_history():
    now = int(time.time())
    names = ["Glass.City.2025.1080p.WEB-DL.x265-ELiTE", "Kitchen.Wars.S03E07.1080p.WEB.x265-MeGusta",
             "Paper.Moons.2022.1080p.BluRay.x265", "The.Cartographers.S03.1080p.WEB-DL.x265-BONE"]
    return [{"name": n, "path": "/media/library", "date": time.strftime("%Y-%m-%d %H:%M", time.localtime(now - i * 7200)),
             "timestamp": now - i * 7200} for i, n in enumerate(names)]


def li_discover():
    def img(item_id):
        return f"{HOST_FROM_EMULATOR}/Items/{item_id}/Images/Primary?maxWidth=300&tag=p{item_id}"
    aired = [{"kind": "episode", "show": ITEMS[s]["Name"], "code": code, "network": net, "image": img(s),
              "query": f"{ITEMS[s]['Name']} {code}", "in_library": lib}
             for s, code, net, lib in [("s1", "S02E07", "Nordic+", True), ("s2", "S02E09", "HBX", True),
                                       ("s4", "S01E09", "Lumen TV", False), ("s6", "S02E05", "Shudder Nine", False),
                                       ("s3", "S03E07", "Atlas One", True)]]
    movies = []
    for m, lib in [("m8", True), ("m2", False), ("m12", False), ("m10", True), ("m4", False), ("m6", False), ("m9", False)]:
        it = ITEMS[m]
        name = it["Name"].replace(" ", ".") + f".{it['ProductionYear']}.1080p.WEB-DL.x265-ELiTE"
        movies.append({"kind": "movie", "title": it["Name"], "year": it["ProductionYear"], "rating": it["CommunityRating"],
                       "image": img(m), "query": it["Name"], "in_library": lib,
                       "direct": None if lib else {"label": "1-click 1080p", "name": name, "magnet": magnet(name)}})
    return {"aired": aired, "movies": movies}


def liserver(method, path, q):
    if path == "/api/auth/me":
        return {"authenticated": True, "user": {"display": "Lolish", "username": "lolish"}, "csrf": "demo"}
    if path == "/api/search":
        return li_search(q.get("q", [""])[0], q.get("mode", ["public"])[0])
    if path == "/api/downloads":
        return li_downloads()
    if path == "/api/history":
        return li_history()
    if path == "/api/discover":
        return li_discover()
    if path == "/api/library":
        return {"paths": ["/media/Movies", "/media/TV Shows"]}
    if path == "/api/plan_download":
        return {"plan": {"crumbs": [{"label": "TV Shows"}, {"label": "Frostline"}, {"label": "Season 2"}],
                         "new_season": True}}
    return {"ok": True}


# --- HTTP -----------------------------------------------------------------------------------------

def make_handler(kind):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, fmt, *args):
            sys.stderr.write(f"[{kind}] {self.command} {self.path}\n")

        def send(self, code, body, ctype):
            self.send_response(code)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def handle_any(self):
            length = int(self.headers.get("Content-Length") or 0)
            if length:
                self.rfile.read(length)
            url = urlparse(self.path)
            q = parse_qs(url.query)
            try:
                if kind == "jellyfin" and "/Images/" in url.path:
                    data = jellyfin_image(url.path, q)
                    if data is None:
                        return self.send(404, b"", "text/plain")
                    return self.send(200, data, "image/jpeg")
                result = jellyfin(url.path, q) if kind == "jellyfin" else liserver(self.command, url.path, q)
                return self.send(200, json.dumps(result).encode(), "application/json")
            except Exception as e:  # keep the app going, but show the problem in the job log
                sys.stderr.write(f"[{kind}] ERROR {self.path}: {e!r}\n")
                return self.send(500, json.dumps({"error": str(e)}).encode(), "application/json")

        do_GET = do_POST = do_DELETE = do_PUT = handle_any

    return Handler


def main():
    servers = [ThreadingHTTPServer(("0.0.0.0", 8096), make_handler("jellyfin")),
               ThreadingHTTPServer(("0.0.0.0", 5000), make_handler("liserver"))]
    for s in servers[1:]:
        threading.Thread(target=s.serve_forever, daemon=True).start()
    print("mock servers on :8096 (Jellyfin) and :5000 (Li'Server)", flush=True)
    servers[0].serve_forever()


if __name__ == "__main__":
    main()
