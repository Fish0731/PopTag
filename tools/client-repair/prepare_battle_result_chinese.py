"""Prepare the native Chinese layout and sprites for battle results."""
import argparse
import struct
from pathlib import Path

import pefile

import repair_dungeon_spawn_tables as assets
from legacy_sprite_codec import convert_sprite, frame_from_sprite


def prepare(client, national, output):
    index_path = client / "fx.idx"
    archive_path = client / "fx.idd"
    entries, key = assets.load_index(index_path)
    national_entries, _ = assets.load_index(national / "fx.idx")
    size = archive_path.stat().st_size
    append = bytearray()
    for name in ("Game/Draw", "Game/Perfect"):
        source = national_entries[name]
        assert source["flags"] == entries[name]["flags"] == 0
        raw = assets.read_asset(national, source)
        sprite, _ = convert_sprite(raw)
        assert struct.unpack_from("<I", sprite)[0] == 3
        for frame in range(3):
            original_frame = frame_from_sprite(raw, frame)
            converted_frame = frame_from_sprite(sprite, frame)
            assert converted_frame.size == original_frame.size and converted_frame.getbbox()
        entries[name] = dict(entries[name], offset=size + len(append), size=len(sprite))
        append.extend(sprite)

    original = (client / "CA.exe").read_bytes()
    patched = bytearray(original)
    pe = pefile.PE(data=original, fast_load=True)
    base = pe.OPTIONAL_HEADER.ImageBase
    # Select the client's existing three-glyph layout for these two captions.
    for site, target, expected in (
        (0x44C529, 0x44C580, "6a13e8c03f2700"),
        (0x44C7EB, 0x44C85B, "6a13e8fe3c2700"),
    ):
        offset = pe.get_offset_from_rva(site - base)
        before = bytes.fromhex(expected)
        assert original[offset:offset + len(before)] == before
        patched[offset:offset + len(before)] = (
            b"\xe9" + struct.pack("<i", target - site - 5) + b"\x90\x90"
        )

    output.mkdir(parents=True, exist_ok=False)
    (output / "CA.exe").write_bytes(patched)
    assets.write_index(output / "fx.idx", entries, key)
    (output / "fx.append").write_bytes(append)
    print("Prepared Chinese draw and perfect-win glyphs.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("client", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--national", type=Path, default=assets.SOURCE)
    args = parser.parse_args()
    prepare(args.client, args.national, args.output)
