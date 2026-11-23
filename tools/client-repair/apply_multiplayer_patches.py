#!/usr/bin/env python3
"""
Apply multiplayer & Monster Mode Stage 2/3 client patches to PopTag EN CA.exe (v17+).

Patches applied (idempotent, verifies expected bytes before patching):
  1. 0x00B95110: Keep Monster Mode (0x0E) alive after single-player death so teammates can rescue.
  2. 0x00B950E0 + 0x00B95300 + 0x00B95360 + 0x0051E11E:
     Enforce guest AllMustReady check and Start button enable/disable in Monster Mode.
  3. 0x0068309A -> 0x00B954A0: Explicitly closesocket() UDP recvfrom handle on exit to prevent hang.
  4. 0x00794440 & 0x006C4E89: Bound WaitForSingleObject(INFINITE) -> 127ms on shutdown/disconnect.
  5. 0x0054AE4A -> 0x00B95580: Keep occupied player slots (0..7) visible in right-side HUD.
  6. 0x00553F43 -> 0x00B95600: Restore [ecx+0x311C]=1 on op 136 (GameBoardPeers) after MonsterNextBoard.
  7. 0x0054FC73 -> 0x00B95620: Preserve CRoomMgr slot [+0x50/+0x54/+0x60] across GameResult (op 150)
     in Monster Mode (0x0E) so Stage 2/3 retains team/ally/rescue state.

Usage:
  python apply_multiplayer_patches.py <path/to/CA.exe> [--check]
"""

from __future__ import annotations

import argparse
import pathlib
import struct
import sys


def p32(val: int) -> bytes:
    return struct.pack("<i", val)


def build_ready_check() -> bytes:
    va = 0x00B95300
    h = bytearray()
    h += bytes([0x53, 0x56, 0x57, 0x83, 0xEC, 0x04])
    h += bytes([0x8B, 0x1D, 0x44, 0x3C, 0x99, 0x00])
    h += bytes([0x85, 0xDB, 0x74])
    jz_ret_ok1 = len(h)
    h.append(0)
    h += bytes([0x8B, 0xBB, 0xDC, 0x01, 0x00, 0x00, 0x85, 0xFF, 0x7E])
    jle_ret_ok2 = len(h)
    h.append(0)
    h += bytes([0x83, 0xFF, 0x08, 0x7E, 0x05, 0xBF, 0x08, 0x00, 0x00, 0x00, 0x33, 0xF6])
    loop_start = len(h)
    h += bytes([0x56, 0x8B, 0xCB, 0xE8]) + p32(0x0068609B - (va + len(h) + 3 + 5))
    h += bytes([0x85, 0xC0, 0x74])
    jz_next1 = len(h)
    h.append(0)
    h += bytes([0x8B, 0xC4, 0x50, 0x56, 0x8B, 0xCB, 0xE8]) + p32(0x00687914 - (va + len(h) + 6 + 5))
    h += bytes([0x8A, 0x04, 0x24, 0xA8, 0x02, 0x75])
    jnz_next2 = len(h)
    h.append(0)
    h += bytes([0xA8, 0x04, 0x74])
    jz_not_ready = len(h)
    h.append(0)
    next_slot = len(h)
    h += bytes([0x46, 0x3B, 0xF7, 0x7C, (loop_start - (len(h) + 5)) & 0xFF])
    ret_ok = len(h)
    h += bytes([0x33, 0xC0])
    epilogue = len(h)
    h += bytes([0x83, 0xC4, 0x04, 0x5F, 0x5E, 0x5B, 0xC3])
    not_ready = len(h)
    h += bytes([0xB8, 0x03, 0x00, 0x00, 0x00, 0xEB, (epilogue - (len(h) + 7)) & 0xFF])

    h[jz_ret_ok1] = (ret_ok - (jz_ret_ok1 + 1)) & 0xFF
    h[jle_ret_ok2] = (ret_ok - (jle_ret_ok2 + 1)) & 0xFF
    h[jz_next1] = (next_slot - (jz_next1 + 1)) & 0xFF
    h[jnz_next2] = (next_slot - (jnz_next2 + 1)) & 0xFF
    h[jz_not_ready] = (not_ready - (jz_not_ready + 1)) & 0xFF
    return bytes(h)


def build_ui_hook() -> bytes:
    va = 0x00B95360
    u = bytearray()
    u += bytes([0xA1, 0x44, 0x3C, 0x99, 0x00, 0x85, 0xC0, 0x74])
    jz_fb1 = len(u)
    u.append(0)
    u += bytes([0x83, 0xB8, 0x70, 0x05, 0x00, 0x00, 0x0E, 0x7C])
    jl_fb2 = len(u)
    u.append(0)
    u.append(0x74)
    je_monster = len(u)
    u.append(0)
    u += bytes([0x83, 0xB8, 0x70, 0x05, 0x00, 0x00, 0x0F, 0x7F])
    jg_fb3 = len(u)
    u.append(0)
    u += bytes([0x33, 0xC0, 0xC3])
    fallback_orig = len(u)
    u += b"\xE9" + p32(0x0051C200 - (va + len(u) + 5))
    monster_check = len(u)
    u.append(0x51)
    u += b"\xE8" + p32(0x00B95300 - (va + len(u) + 5))
    u += bytes([0x59, 0x85, 0xC0, 0x74])
    jz_enable = len(u)
    u.append(0)
    u += bytes([0x83, 0xB9, 0x70, 0x73, 0x00, 0x00, 0x00, 0x74])
    je_done1 = len(u)
    u.append(0)
    u += bytes([0x50, 0x6A, 0x00, 0x81, 0xC1, 0x04, 0x73, 0x00, 0x00])
    u += b"\xE8" + p32(0x0079E2F6 - (va + len(u) + 5))
    u.append(0x58)
    done1 = len(u)
    u.append(0xC3)
    enable_btn = len(u)
    u += bytes([0x83, 0xB9, 0x78, 0x2F, 0x00, 0x00, 0x00, 0x75])
    jne_done2 = len(u)
    u.append(0)
    u += bytes([0x83, 0xB9, 0x70, 0x73, 0x00, 0x00, 0x00, 0x75])
    jne_done3 = len(u)
    u.append(0)
    u += bytes([0x6A, 0x01, 0x81, 0xC1, 0x04, 0x73, 0x00, 0x00])
    u += b"\xE8" + p32(0x0079E2F6 - (va + len(u) + 5))
    u += bytes([0x33, 0xC0])
    done2 = len(u)
    u.append(0xC3)

    u[jz_fb1] = (fallback_orig - (jz_fb1 + 1)) & 0xFF
    u[jl_fb2] = (fallback_orig - (jl_fb2 + 1)) & 0xFF
    u[je_monster] = (monster_check - (je_monster + 1)) & 0xFF
    u[jg_fb3] = (fallback_orig - (jg_fb3 + 1)) & 0xFF
    u[jz_enable] = (enable_btn - (jz_enable + 1)) & 0xFF
    u[je_done1] = (done1 - (je_done1 + 1)) & 0xFF
    u[jne_done2] = (done2 - (jne_done2 + 1)) & 0xFF
    u[jne_done3] = (done2 - (jne_done3 + 1)) & 0xFF
    return bytes(u)


def build_close_udp() -> bytes:
    c = bytearray()
    c += bytes([0xA1, 0x74, 0x05, 0x9F, 0x00, 0x85, 0xC0, 0x74])
    jz1 = len(c)
    c.append(0)
    c += bytes([0x8B, 0x50, 0x08, 0x85, 0xD2, 0x74])
    jz2 = len(c)
    c.append(0)
    c += bytes([0x83, 0xFA, 0xFF, 0x74])
    jz3 = len(c)
    c.append(0)
    c += bytes([0xC7, 0x40, 0x08, 0xFF, 0xFF, 0xFF, 0xFF, 0x50, 0x52])
    c += bytes([0xFF, 0x15, 0xFC, 0x95, 0x88, 0x00, 0x58])
    done = len(c)
    c.append(0xC3)
    c[jz1] = (done - (jz1 + 1)) & 0xFF
    c[jz2] = (done - (jz2 + 1)) & 0xFF
    c[jz3] = (done - (jz3 + 1)) & 0xFF
    return bytes(c)


def build_hud_cave() -> bytes:
    va = 0x00B95580
    c = bytearray()
    c += bytes([0x8D, 0x4E, 0x08])
    c += b"\xE8" + p32(0x0044B790 - (va + len(c) + 5))
    c += bytes([0x85, 0xC0, 0x75])
    jnz_done = len(c)
    c.append(0)
    c += bytes([0x8D, 0x8E, 0x84, 0x00, 0x00, 0x00])
    c += b"\xE8" + p32(0x0044B790 - (va + len(c) + 5))
    c += bytes([0x83, 0xF8, 0x08, 0x73])
    jae_zero = len(c)
    c.append(0)
    c += bytes([0x8B, 0x0D, 0x44, 0x3C, 0x99, 0x00, 0x85, 0xC9, 0x74])
    jz_zero = len(c)
    c.append(0)
    c.append(0x50)
    c += b"\xE8" + p32(0x0068609B - (va + len(c) + 5))
    c.append(0xC3)
    ret_zero = len(c)
    c += bytes([0x33, 0xC0])
    ret_done = len(c)
    c.append(0xC3)
    c[jnz_done] = (ret_done - (jnz_done + 1)) & 0xFF
    c[jae_zero] = (ret_zero - (jae_zero + 1)) & 0xFF
    c[jz_zero] = (ret_zero - (jz_zero + 1)) & 0xFF
    return bytes(c)


def apply_patch(buf: bytearray, name: str, offset: int, expected: bytes, replacement: bytes, check_only: bool) -> bool:
    cur = bytes(buf[offset : offset + len(replacement)])
    if cur == replacement:
        print(f"  [OK] {name} already applied")
        return False
    orig = bytes(buf[offset : offset + len(expected)])
    if orig != expected:
        raise RuntimeError(
            f"{name}: unexpected bytes at 0x{offset:X} (got {orig[:12].hex()}, expected {expected[:12].hex()})"
        )
    if not check_only:
        buf[offset : offset + len(replacement)] = replacement
        print(f"  [PATCHED] {name} @ 0x{offset:X} ({len(replacement)}B)")
    else:
        print(f"  [READY] {name} @ 0x{offset:X}")
    return True


def patch_ca_exe(path: pathlib.Path, check_only: bool = False) -> None:
    data = bytearray(path.read_bytes())
    changed = False

    # 1. Monster Mode single-death continuation (0x00B95110)
    orig_5110 = bytes([
        0xA1, 0x44, 0x3C, 0x99, 0x00,
        0x83, 0xB8, 0x70, 0x05, 0x00, 0x00, 0x0F,
        0x74, 0x05, 0xE9, 0x49, 0x32, 0x9B, 0xFF,
        0xB8, 0x01, 0x00, 0x00, 0x00, 0xC3,
    ]) + bytes(23)
    rep_5110 = bytes([
        0xA1, 0x44, 0x3C, 0x99, 0x00,
        0x83, 0xB8, 0x70, 0x05, 0x00, 0x00, 0x0E, 0x74, 0x0E,
        0x83, 0xB8, 0x70, 0x05, 0x00, 0x00, 0x0F, 0x74, 0x05,
        0xE9, 0x40, 0x32, 0x9B, 0xFF,
        0xB8, 0x01, 0x00, 0x00, 0x00, 0xC3,
    ]) + bytes(14)
    changed |= apply_patch(data, "MonsterSingleDeath (0x00B95110)", 0x6E1110, orig_5110, rep_5110, check_only)

    # 2. Monster Mode AllMustReady & Start button UI hook
    orig_50e0 = bytes([
        0xA1, 0x44, 0x3C, 0x99, 0x00,
        0x83, 0xB8, 0x70, 0x05, 0x00, 0x00, 0x0E, 0x7C, 0x09,
        0x83, 0xB8, 0x70, 0x05, 0x00, 0x00, 0x0F, 0x7E, 0x05,
        0xE9, 0x04, 0x71, 0x98, 0xFF, 0x33, 0xC0, 0xC3,
    ]) + bytes(17)
    rep_50e0 = (
        bytes([
            0xA1, 0x44, 0x3C, 0x99, 0x00,
            0x83, 0xB8, 0x70, 0x05, 0x00, 0x00, 0x0E, 0x7C, 0x0B, 0x74, 0x11,
            0x83, 0xB8, 0x70, 0x05, 0x00, 0x00, 0x0F, 0x7E, 0x05,
        ])
        + b"\xE9" + p32(0x0051C200 - (0x00B950E0 + 30))
        + bytes([0x33, 0xC0, 0xC3])
        + b"\xE9" + p32(0x00B95300 - (0x00B950E0 + 38))
        + bytes(10)
    )
    ready_cave = build_ready_check()
    ui_cave = build_ui_hook()
    changed |= apply_patch(data, "ReadyDispatcher (0x00B950E0)", 0x6E10E0, orig_50e0, rep_50e0, check_only)
    changed |= apply_patch(data, "MonsterReadyCheck (0x00B95300)", 0x6E1300, bytes(len(ready_cave)), ready_cave, check_only)
    changed |= apply_patch(data, "MonsterStartBtnUi (0x00B95360)", 0x6E1360, bytes(len(ui_cave)), ui_cave, check_only)
    changed |= apply_patch(
        data,
        "PrepareStageUiCall (0x0051E11E)",
        0x11E11E,
        bytes([0xE8, 0xDD, 0xE0, 0xFF, 0xFF]),
        b"\xE8" + p32(0x00B95360 - (0x0051E11E + 5)),
        check_only,
    )

    # 3. Exit UDP socket close hook
    close_udp = build_close_udp()
    changed |= apply_patch(data, "CloseUdpCave (0x00B954A0)", 0x6E14A0, bytes(len(close_udp)), close_udp, check_only)
    changed |= apply_patch(
        data,
        "ExitCloseUdpCall (0x0068309A)",
        0x28309A,
        bytes([0xA1, 0x74, 0x05, 0x9F, 0x00]),
        b"\xE8" + p32(0x00B954A0 - (0x0068309A + 5)),
        check_only,
    )

    # 4. Bounded WaitForSingleObject timeout (0xFF -> 0x7F = 127ms)
    changed |= apply_patch(data, "StopAndWaitTimeout (0x00794440)", 0x394440, bytes([0x6A, 0xFF, 0x56]), bytes([0x6A, 0x7F, 0x56]), check_only)
    changed |= apply_patch(data, "NetWaitTimeout (0x006C4E89)", 0x2C4E89, bytes([0x6A, 0xFF, 0x53]), bytes([0x6A, 0x7F, 0x53]), check_only)

    # 5. Multiplayer HUD visibility hook
    hud_cave = build_hud_cave()
    changed |= apply_patch(data, "HudVisCave (0x00B95580)", 0x6E1580, bytes(len(hud_cave)), hud_cave, check_only)
    changed |= apply_patch(
        data,
        "HudVisCall (0x0054AE4A)",
        0x14AE4A,
        bytes([0x8D, 0x4E, 0x08, 0xE8, 0x3E, 0x09, 0xF0, 0xFF]),
        b"\xE8" + p32(0x00B95580 - (0x0054AE4A + 5)) + bytes([0x90, 0x90, 0x90]),
        check_only,
    )

    # 6. Monster Mode Stage 2/3 op 136 sync ([ecx+0x311C] = 1)
    stage2_cave = bytes([0xC7, 0x81, 0x1C, 0x31, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00]) + b"\xE9" + p32(0x005536EB - (0x00B95600 + 15))
    changed |= apply_patch(data, "Stage2Op136Cave (0x00B95600)", 0x6E1600, bytes(len(stage2_cave)), stage2_cave, check_only)
    changed |= apply_patch(
        data,
        "Stage2Op136Call (0x00553F43)",
        0x153F43,
        bytes([0xE8, 0xA3, 0xF7, 0xFF, 0xFF]),
        b"\xE8" + p32(0x00B95600 - (0x00553F43 + 5)),
        check_only,
    )

    # 7. Preserve CRoomMgr slot [+0x50/+0x54/+0x60] across GameResult (op 150) in Monster Mode (0x0E)
    preserve_cave = (
        bytes([0x83, 0xBB, 0x70, 0x05, 0x00, 0x00, 0x0E, 0x0F, 0x84])
        + p32(0x0054FC97 - (0x00B95620 + 13))
        + bytes([0x6A, 0xFF, 0xFF, 0x75, 0x40, 0xE9])
        + p32(0x0054FC78 - (0x00B95620 + 23))
    )
    changed |= apply_patch(data, "GameResultPreserveCave (0x00B95620)", 0x6E1620, bytes(len(preserve_cave)), preserve_cave, check_only)
    changed |= apply_patch(
        data,
        "GameResultPreserveJmp (0x0054FC73)",
        0x14FC73,
        bytes([0x6A, 0xFF, 0xFF, 0x75, 0x40]),
        b"\xE9" + p32(0x00B95620 - (0x0054FC73 + 5)),
        check_only,
    )

    if not check_only and changed:
        backup = path.with_suffix(".exe.pre-mp.bak")
        if not backup.exists():
            backup.write_bytes(path.read_bytes())
        path.write_bytes(bytes(data))
        print(f"Successfully wrote patched client: {path}")
    elif check_only:
        print("Check completed cleanly.")
    else:
        print("All patches were already applied; no changes written.")


def main() -> int:
    parser = argparse.ArgumentParser(description="Apply PopTag multiplayer & Monster Stage 2/3 client patches.")
    parser.add_argument("ca_exe", type=pathlib.Path, help="Path to client/CA.exe")
    parser.add_argument("--check", action="store_true", help="Verify patch sites without modifying the binary")
    args = parser.parse_args()
    patch_ca_exe(args.ca_exe, check_only=args.check)
    return 0


if __name__ == "__main__":
    sys.exit(main())
