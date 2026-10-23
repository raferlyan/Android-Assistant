#!/usr/bin/env python3
"""生成 app/src/main/assets/pinyin_table.txt：每行 "汉字 声母 韵母(带调)"。

规则与 sherpa-onnx 的 text2token (ppinyin) 一致：pypinyin strict=False，
所以 y/w 被当作声母，零声母字只有韵母一个 token。

用法: pip install pypinyin && python3 tools/gen_pinyin_table.py
"""
import os
import sys

from pypinyin import Style, pinyin

out = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "pinyin_table.txt")
os.makedirs(os.path.dirname(out), exist_ok=True)

n = 0
with open(out, "w", encoding="utf-8") as f:
    for code in range(0x4E00, 0x9FFF + 1):
        ch = chr(code)
        ini = pinyin(ch, style=Style.INITIALS, strict=False, errors="ignore")
        fin = pinyin(ch, style=Style.FINALS_TONE, strict=False, errors="ignore")
        if not fin or not fin[0] or not fin[0][0] or fin[0][0] == ch:
            continue
        i = ini[0][0] if ini and ini[0] else ""
        f.write(f"{ch} {i} {fin[0][0]}\n" if i else f"{ch}  {fin[0][0]}\n")
        n += 1
print(f"wrote {n} chars -> {os.path.normpath(out)}", file=sys.stderr)
