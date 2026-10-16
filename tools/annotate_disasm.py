#!/usr/bin/env python3
"""Annotate a thumb/arm disassembly listing by resolving `<.plt+0xNN>` targets to symbols.

Usage: annotate_disasm.py lib.so disasm.txt > disasm-annotated.txt
"""
import re
import subprocess
import sys

sys.path.insert(0, __file__.rsplit("/", 1)[0])
from plt_symbols import relocations, sections  # noqa: E402


def plt_map(so):
    plt_addr, _, plt_size = sections(so)[".plt"]
    entries = [e for e in relocations(so) if e[1] in ("R_ARM_JUMP_SLOT", "R_AARCH64_JUMP_SLOT")]
    stub_size, header = 12, 20
    assert plt_size - header == len(entries) * stub_size, (plt_size, len(entries))
    return {plt_addr + header + i * stub_size: name for i, (_, _, name) in enumerate(entries)}


def main():
    so, listing = sys.argv[1], sys.argv[2]
    m = plt_map(so)
    pattern = re.compile(r"<(?:\.plt\+0x[0-9a-f]+)>")
    out = []
    for line in open(listing, errors="replace"):
        match = re.search(r"<(?:\.plt\+0x([0-9a-f]+))>", line)
        if match:
            stub = 0x1A12C + int(match.group(1), 16)  # .plt is at 0x1a12c in this library
            name = m.get(stub)
            if name:
                line = pattern.sub(f"<{name}>", line, count=1)
        out.append(line)
    sys.stdout.writelines(out)


if __name__ == "__main__":
    main()
