"""Rebuild dungeon boards without treating the variable spawn table as tiles."""
import hashlib
import json
from pathlib import Path
import struct
import sys

ROOT = Path(__file__).resolve().parent
WORK = ROOT.parent
sys.path.insert(0, str(WORK / "PopTag-shop"))
from idd_assets import load_index, read_asset
from rewrite_fx_index import write_index

codec = WORK / "PopTag-monster-port/fix_map_object_indices.py"
source_text = codec.read_text(encoding="utf-8-sig")
# Use the same codec as the original import, independently of archived paths.
scope = {"struct": struct, "read_asset": read_asset}
decrypt_file = WORK / "PopTag-monster-port/server/tools/decrypt_attr.py"
exec(decrypt_file.read_text(encoding="utf-8-sig").split("with open(")[0], scope)
scope["decrypt"] = scope["decrypt_idd_file"]
exec(source_text[source_text.index("def objects("):source_text.index("def main(")], scope)
decrypt, encrypt, crc, objects = (scope[key] for key in ("decrypt", "encrypt", "crc", "objects"))

CLIENT = ROOT / "client"
SOURCE = next(p / "\u56fd\u670d 057\u7248" for p in Path("D:/GAME").iterdir()
              if (p / "\u56fd\u670d 057\u7248").exists())
OUT = ROOT / "port-audit/dungeon-spawn-fix"
EMPTY = 0xffffffff
SPAWN_TABLE = 0x4c


def tile_offset(plain, capacity):
    extra_count_offset = SPAWN_TABLE + capacity * 8
    extra_count, = struct.unpack_from("<I", plain, extra_count_offset)
    return extra_count_offset + 4 + extra_count * 16


def digest(data):
    return hashlib.sha256(data).hexdigest()


def convert(plain, source_names, target_indexes):
    width, height, capacity = struct.unpack_from("<III", plain, 12)
    assert (width, height) == (15, 13) and capacity in (4, 6, 8)
    grid = tile_offset(plain, capacity)
    end = grid + width * height * 8
    assert end <= len(plain)
    walkable = set()
    converted = bytearray(plain)
    for cell, (floor, obstacle) in enumerate(struct.iter_unpack("<II", plain[grid:end])):
        if obstacle == EMPTY and floor != EMPTY:
            walkable.add(divmod(cell, width))
        for layer, value in enumerate((floor, obstacle)):
            if value != EMPTY:
                struct.pack_into("<I", converted, grid + cell * 8 + layer * 4,
                                 target_indexes[source_names[value]])
    assert converted[end:] == plain[end:]

    spawns_end = SPAWN_TABLE + capacity * 8
    spawns = [(row, column) for column, row in
              struct.iter_unpack("<II", plain[SPAWN_TABLE:spawns_end])]
    ordered = [slot for slot in (2, 1, 6, 0, 3, 4, 5, 7) if slot < capacity]
    used = set()
    keep = set()
    # Reserve every usable source coordinate before replacing duplicates.
    for slot in ordered:
        point = spawns[slot]
        if point in walkable and point not in used:
            used.add(point)
            keep.add(slot)
    for slot in ordered:
        if slot in keep:
            continue
        y, x = spawns[slot]
        point = min(walkable - used, key=lambda rc: (abs(rc[0] - y) + abs(rc[1] - x), rc))
        spawns[slot] = point
        used.add(point)
        struct.pack_into("<II", converted, SPAWN_TABLE + slot * 8, point[1], point[0])
    assert len(set(spawns)) == capacity and set(spawns) <= walkable
    assert converted[:SPAWN_TABLE] == plain[:SPAWN_TABLE]
    assert converted[spawns_end:grid] == plain[spawns_end:grid]
    return converted, spawns


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    base_index = (CLIENT / "fx.idx").read_bytes()
    target, key = load_index(CLIENT / "fx.idx")
    original, _ = load_index(SOURCE / "fx.idx")
    source_names = list(objects(SOURCE, original))
    target_indexes = {name: i for i, name in enumerate(objects(CLIENT, target))}
    archive_size = (CLIENT / "fx.idd").stat().st_size
    append = bytearray()
    maps = {}
    for number in range(1, 23):
        name = f"Map/Tomb{number:02d}"
        item = original[name]
        plain = decrypt(read_asset(SOURCE, item, "fx"), item["flags"])
        converted, spawns = convert(plain, source_names, target_indexes)
        converted += bytes((-len(converted)) % 4)
        encoded = encrypt(converted, item["flags"])
        assert decrypt(encoded, item["flags"]) == converted
        target[name] = dict(target[name], offset=archive_size + len(append),
                            size=len(encoded), flags=item["flags"], key=crc(converted, item["flags"]))
        append.extend(encoded)
        maps[name] = spawns
    (OUT / "fx.idx.before").write_bytes(base_index)
    write_index(OUT / "fx.idx.candidate", target, key)
    verify, _ = load_index(OUT / "fx.idx.candidate")
    assert all(verify[name] == target[name] for name in maps)
    (OUT / "fx.append.bin").write_bytes(append)
    manifest = dict(archiveSize=archive_size, beforeIndex=digest(base_index),
                    afterIndex=digest((OUT / "fx.idx.candidate").read_bytes()), spawns=maps)
    (OUT / "manifest.json").write_text(json.dumps(manifest, indent=2))
    print(f"Validated {len(maps)} dungeons, {sum(map(len, maps.values()))} distinct in-bounds starts")


if __name__ == "__main__":
    main()
