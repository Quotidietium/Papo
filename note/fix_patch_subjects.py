# -*- coding: utf-8 -*-
"""把 feature 补丁文件里 RFC2047 编码（中文）的 Subject 头改写为与文件名一致的干净英文。

根除 build.md 记录的"rebuildPatches 垃圾重命名"复发问题：rebuildPatches 以内部仓库提交
subject 重新生成补丁文件名，而内部 subject 又来自补丁文件的 Subject 头——中文 Subject
经 slug 退化成垃圾名（如 0168-0168.patch）。把 Subject 头改写为"文件名体（'-'→空格）"
后，slug 往返闭合，文件名从此稳定。

用法：python note/fix_patch_subjects.py [--check]
  默认改写；--check 只统计不改。处理 paper-server/patches/features/*.patch。
字节级安全：仅替换头部区（Subject 行 + 其 RFC2047 续行），正文与行尾完全不动。
"""
import glob
import os
import re
import sys

FEATURES = os.path.join(os.path.dirname(__file__), os.pardir, "paper-server", "patches", "features")


def derive_subject(filename: str) -> str:
    stem = filename[:-len(".patch")]
    # 去掉 NNNN- 序号前缀
    stem = re.sub(r"^\d+-", "", stem)
    # slug 往返：文件名体的 '-' 还原为空格（paperweight 的 slug 把空格变 '-'，点号等保留）
    return stem.replace("-", " ").rstrip()


def main() -> None:
    check_only = "--check" in sys.argv
    changed = 0
    for path in sorted(glob.glob(os.path.join(FEATURES, "*.patch"))):
        with open(path, "rb") as f:
            data = f.read()
        lines = data.split(b"\n")
        # 头部区：到首个空行为止
        hdr_end = next((i for i, ln in enumerate(lines) if ln == b""), len(lines))
        subj_idx = next((i for i, ln in enumerate(lines[:hdr_end]) if ln.startswith(b"Subject: ")), None)
        if subj_idx is None:
            continue
        # Subject 行 + 其续行（以空格开头且仍是编码词/文本续行）
        cont_end = subj_idx + 1
        while cont_end < hdr_end and lines[cont_end].startswith(b" "):
            cont_end += 1
        block = b"\n".join(lines[subj_idx:cont_end])
        if b"=?UTF-8?" not in block and b"=?utf-8?" not in block:
            continue
        new_subject = ("Subject: [PATCH] " + derive_subject(os.path.basename(path))).encode("utf-8")
        lines[subj_idx:cont_end] = [new_subject]
        out = b"\n".join(lines)
        assert out == data or True
        if not check_only:
            with open(path, "wb") as f:
                f.write(out)
        changed += 1
    print(("check: " if check_only else "rewrote: ") + str(changed) + " patch files")


if __name__ == "__main__":
    main()
