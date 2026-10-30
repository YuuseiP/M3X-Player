"""Validate physical screenshot files and create a Markdown review index."""
import hashlib
import json
from pathlib import Path
import re
import struct
import sys


def build(folder):
    manifest = json.loads((folder / 'manifest.json').read_text(encoding='utf-8-sig'))
    host = json.loads((folder / 'host.json').read_text(encoding='utf-8-sig'))
    log = folder / 'instrumentation.txt'
    completed = log.exists() and re.search(r'OK \(1 test\)', log.read_text(encoding='utf-8-sig')) is not None
    manifest['captureComplete'] = completed
    pages = manifest['pages']
    lines = ['# M3X 真机外观截图', '',
             f"设备：{manifest['device']} · Android {manifest['android']} · 已安装版本 {host['installedVersion']}", '',
             '执行状态：' + ('完整通过，保存的 UI/工具设置已恢复。' if completed else '未完整通过；以下仅为已拉回的部分证据，请先检查 instrumentation.txt。'), '',
             '来源：通过 ADB 启动匹配发布签名的 instrumentation，使用 UiAutomation 截取物理显示屏。保留状态栏与导航栏；没有浏览器夹具。', '',
             '后续 agent 先读取本文件及 manifest.json，再打开所涉 PNG。用户可以直接在 PNG 副本上标注，并在同目录 REVIEW.md 中记录截图 ID、修改意图和标注文件路径。', '',
             '| 截图 ID | 页面 | 结果 |', '| --- | --- | --- |']
    count = 0
    seen = set()
    for page in pages:
        assert page['id'] not in seen, 'Duplicate capture ID'
        seen.add(page['id'])
        if page['status'] == 'captured':
            name = page['file']
            assert Path(name).name == name and name.endswith('.png'), 'Invalid screenshot filename'
            data = (folder / name).read_bytes()
            assert data[:8] == b'\x89PNG\r\n\x1a\n', f'Invalid PNG: {name}'
            assert struct.unpack('>II', data[16:24]) == (page['width'], page['height']), name
            page['sha256'] = hashlib.sha256(data).hexdigest()
            result = f'[打开截图]({name})'
            count += 1
        else:
            result = '未拍摄：' + page['reason']
        lines.append(f"| {page['id']} | {page['title']} | {result.replace('|', '/')} |")
    lines += ['', f'已保存 {count} 张真机截图；未拍摄状态见上表。', '',
              '截图仅用于外观审阅，不代表扫描、DSP、联网、标签写入或硬件输出功能已通过验收。']
    (folder / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    (folder / 'INDEX.md').write_text('\n'.join(lines) + '\n', encoding='utf-8')
    print(f'Validated {count} physical screenshots: {folder / "INDEX.md"}')


if __name__ == '__main__':
    build(Path(sys.argv[1]).resolve(strict=True))
