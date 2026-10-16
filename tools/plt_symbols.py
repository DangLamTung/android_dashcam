#!/usr/bin/env python3
"""Map PLT stub addresses to imported symbol names in an ARM (thumb) shared library.

For lazy-binding ARM shared objects each `.rel.plt` GOT slot holds the address of the
PLT stub that serves that symbol, so reading the word at the slot gives stub -> name.
Usage: plt_symbols.py lib.so [address ...]
"""
import re
import struct
import subprocess
import sys

LLVM_BIN = "/Users/tungdl/Library/Android/sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/darwin-x86_64/bin"


def sections(so):
    out = subprocess.run([f"{LLVM_BIN}/llvm-readelf", "-S", so], capture_output=True, text=True).stdout
    secs = {}
    for line in out.splitlines():
        m = re.match(r"\s*\[\s*\d+\]\s+(\S+)\s+(\S+)\s+([0-9a-f]{8})\s+([0-9a-f]{6,})\s+([0-9a-f]+)", line)
        if m:
            secs[m.group(1)] = (int(m.group(3), 16), int(m.group(4), 16), int(m.group(5), 16))
    return secs


def relocations(so):
    out = subprocess.run([f"{LLVM_BIN}/llvm-readelf", "-r", so], capture_output=True, text=True).stdout
    entries = []
    for line in out.splitlines():
        m = re.match(r"^([0-9a-f]{8})\s+[0-9a-f]+\s+(R_ARM_\w+)\s+[0-9a-f]+\s+(\S+)", line)
        if m:
            entries.append((int(m.group(1), 16), m.group(2), m.group(3)))
    return entries


def main():
    so = sys.argv[1]
    wanted = {int(a, 16) & ~1 for a in sys.argv[2:]}
    secs = sections(so)
    plt_addr, _, plt_size = secs[".plt"]
    entries = [e for e in relocations(so) if e[1] in ("R_ARM_JUMP_SLOT", "R_AARCH64_JUMP_SLOT")]
    # ARM PLT: 20-byte header followed by 12-byte stubs, one per .rel.plt entry, in order
    # (verified: stub 0x1a1a0 == index 8 == pthread_mutex_lock).
    stub_size, header = 12, 20
    if plt_size - header != len(entries) * stub_size:
        print(f"# warning: plt layout mismatch ({plt_size=:#x} {len(entries)=})", file=sys.stderr)
    stub_to_sym = {plt_addr + header + i * stub_size: name for i, (_, _, name) in enumerate(entries)}
    if wanted:
        for a in sorted(wanted):
            print(f"0x{a:x} -> {stub_to_sym.get(a, '?')}")
    else:
        for stub, name in sorted(stub_to_sym.items()):
            print(f"0x{stub:x} {name}")


if __name__ == "__main__":
    main()
