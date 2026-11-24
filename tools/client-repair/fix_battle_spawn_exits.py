"""Repair overlapping and boxed-in starts in the installed battle maps."""
import argparse
import os
from pathlib import Path
import struct

import repair_dungeon_spawn_tables as assets
from rewrite_fx_index import write_index


def repair(raw, *, expand=False):
    w, h, capacity = struct.unpack_from("<III", raw, 12)
    assert (w, h) == (15, 13) and capacity in (4, 6, 8)
    spawns_end = assets.SPAWN_TABLE + 8 * capacity
    grid = assets.tile_offset(raw, capacity)
    assert grid + 8 * w * h <= len(raw)
    cells = struct.iter_unpack("<II", raw[grid:grid + 8 * w * h])
    walkable = {divmod(i, w) for i, (floor, obstacle) in enumerate(cells)
                if floor != assets.EMPTY and obstacle == assets.EMPTY}
    options = {p for p in walkable if any((p[0] + y, p[1] + x) in walkable
               for y, x in ((1, 0), (-1, 0), (0, 1), (0, -1)))}
    positions = [(row, column) for column, row in
                 struct.iter_unpack("<II", raw[assets.SPAWN_TABLE:spawns_end])]
    used = set()
    fix = []
    for slot in (2, 1, 6, 0, 3, 4, 5, 7):
        if slot >= capacity:
            continue
        point = positions[slot]
        if point in options and point not in used:
            used.add(point)
        else:
            fix.append(slot)
    result = bytearray(raw)
    for slot in fix:
        row, column = positions[slot]
        point = min(options - used, key=lambda p: (abs(p[0] - row) + abs(p[1] - column), p))
        used.add(point)
        struct.pack_into("<II", result, assets.SPAWN_TABLE + slot * 8, point[1], point[0])
    assert len(used) == capacity and result[:assets.SPAWN_TABLE] == raw[:assets.SPAWN_TABLE]
    assert result[spawns_end:] == raw[spawns_end:]
    if expand and capacity == 4:
        # Spread the new starts around the existing free pockets.
        extra = bytearray()
        for _ in range(4):
            point = max(sorted(options - used), key=lambda p: min(
                abs(p[0] - y) + abs(p[1] - x) for y, x in used))
            used.add(point)
            extra.extend(struct.pack("<II", point[1], point[0]))
        struct.pack_into("<I", result, 20, 8)
        result[spawns_end:spawns_end] = extra
        assert result[spawns_end + len(extra):] == raw[spawns_end:]
        return result, len(fix) + 4
    return result, len(fix)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("client", type=Path)
    parser.add_argument("backup", type=Path)
    parser.add_argument("--install", action="store_true")
    expansion = parser.add_mutually_exclusive_group()
    expansion.add_argument("--expand-cemetery19", action="store_const", const="Cemetery19", dest="expand_map")
    expansion.add_argument("--expand-tomb21", action="store_const", const="Tomb21", dest="expand_map")
    expansion.add_argument("--restore-dungeons", action="store_true")
    args = parser.parse_args()
    idx = args.client / "fx.idx"
    idd = args.client / "fx.idd"
    baseline = idx.read_bytes()
    entries, key = assets.load_index(idx)
    size = idd.stat().st_size
    added = bytearray()
    if args.restore_dungeons:
        maps = tuple(f"Tomb{i:02d}" for i in range(1, 23))
        source, _ = assets.load_index(assets.SOURCE / "fx.idx")
        source_names = list(assets.objects(assets.SOURCE, source))
        target_indexes = {name: i for i, name in enumerate(assets.objects(args.client, entries))}
    else:
        maps = (args.expand_map,) if args.expand_map else (
            "Cemetery19", *(f"Tomb{i:02d}" for i in range(1, 23)))
    for name in maps:
        resource = f"Map/{name}"
        entry = entries[resource]
        flags = entry["flags"]
        raw = assets.decrypt(assets.read_asset(args.client, entry), flags)
        if args.restore_dungeons:
            original = source[resource]
            source_raw = assets.decrypt(assets.read_asset(assets.SOURCE, original), original["flags"])
            converted, _ = assets.convert(source_raw, source_names, target_indexes)
            fixed, _ = repair(converted, expand=name == "Tomb21")
            fixed.extend(bytes((-len(fixed)) % 4))
            changed = fixed != raw
        else:
            fixed, changed = repair(raw, expand=bool(args.expand_map))
        if not changed:
            continue
        fixed.extend(bytes((-len(fixed)) % 4))
        encoded = assets.encrypt(fixed, flags)
        assert assets.decrypt(encoded, flags) == fixed
        assert repair(fixed)[1] == 0
        entries[resource] = dict(entry, offset=size + len(added), size=len(encoded), key=assets.crc(fixed, flags))
        added.extend(encoded)
        print(f"{name}: updated map asset")
    if not args.install or not added:
        return
    args.backup.mkdir(parents=True)
    (args.backup / "fx.idx.before").write_bytes(baseline)
    candidate = args.backup / "fx.idx.candidate"
    write_index(candidate, entries, key)
    with idd.open("r+b") as stream:
        stream.seek(0, os.SEEK_END)
        assert stream.tell() == size and idx.read_bytes() == baseline
        stream.write(added)
        stream.flush()
        os.fsync(stream.fileno())
        try:
            candidate.replace(idx)
        except OSError:
            stream.truncate(size)
            raise


if __name__ == "__main__":
    main()
