# 0.6.0 外观改动与实体 M3X 验证

2026-10-30，Shanling M3X / Android 7.1.1 / API25，ADB `a6840768`。按原始截图批次 `appearance-20261029-194102` 的草稿修订 **70** 实施，原签名覆盖安装；未卸载、清数据、扫描曲库或修改歌曲标签。

## 已交付

- 曲库列表/双列网格，分类详情、分页、独立滚动位置和重开恢复。
- 经典主题的封面动态配色：取色缓存、失败回退、快速切歌异步结果隔离。
- 歌词右下角更多菜单、独立时间调整面板、保存与写入解释。
- 我的收藏/歌单封面预览；统计新增日/小时聚合、图表与封面排行，保留旧累计。
- 内置“终末地 · 工业”：浅深色、原创静态等高线与点阵、分区线、信号黄、封面边框及统计刻度。参考与设计取舍见 [视觉研究](ENDFIELD_VISUAL_REFERENCES.md)。
- 修复真机发现的网格纵向排列、深色高度裁切及深色状态栏图标问题。

选择入口：我的 → 设置 → 界面主题 → 终末地 · 工业。测试结束恢复原界面设置，用户可自行切换；未强制永久更改用户主题。

## 验证证据

| 检查 | 结果 | 证据 |
| --- | --- | --- |
| JS 配色 | PASS；4320 对比度组合，异步顺序、缓存、回退及主题隔离 | `node android/test_appearance.cjs` |
| JVM | 28 tests，0 failures/errors/skips；其中统计 5 项 | `app/build/test-results/testDebugUnitTest/` |
| Release 构建/Lint | 构建成功；0 errors / 5 既有 warnings | `captures/physical-060-install/endfield-build.txt`、`app/build/reports/lint-results-release.txt` |
| 完整物理截图 | 131 张，`OK (1 test)`；后来复查全部 PNG 非空白 | [完整批次](captures/appearance-20261030-202625/INDEX.md) |
| 真实曲库功能 | `OK (1 test)`；搜索/排序/滚动/关闭封面、封面换色和快速切歌、暂停/播放统计 | [runner](captures/physical-060-install/device-functional.txt) |
| 最终背景图形复测 | 15 张，`OK (1 test)`；浅深色、实际 SVG 解码、导航、系统栏、重开恢复 | [最终批次](captures/appearance-20261030-210732/INDEX.md) |
| 数据保留 | 4158 首；扫描不变；原 UI/音频设置、统计及歌曲/位置恢复 | 捕获 manifest / 功能测试 result.json |
| 安装一致性 | 从设备拉回 base.apk 与交付 APK 逐字节哈希一致 | `dist/verification-0.6.0.json` |

真实播放测试为 4.320 秒静音，解码器 libflac，初始化输出 96000 Hz；只用于验证交互和统计，不代表听感、模拟 DAC 或 bit-perfect 验收。旧累计 126 分钟/48 次保留；旧记录没有历史时段，不伪造历史图表。设备没有歌单，空歌单界面按事实验收，未创建夹具。

## 失败记录与工具修复

- `appearance-20261030-201008` 虽 runner 完成，人工看图发现布局问题；已修复，不用该批次签收。
- `appearance-20261030-201951` 为失败的部分截图：紧凑导航原有 44dp，测试误要求 48dp；退出 pagehide 还可能覆盖临时设置，已修复清理时序。
- `appearance-20261030-204650` 为失败批次：范围参数未生效、设备息屏导致黑图和触屏操作失败。测试窗口改用临时保持亮屏、显式检查屏幕唤醒和截图颜色采样；不修改系统永久设置。
- `appearance-20261030-205316` 为图形初稿的 15 张成功截图；人工复核后降低纹理密度与强度，最终以 `210732` 批次为准。

最终 APK：`dist/Cheng-M3X-0.6.0.apk`（versionCode 7），附 `.apk.sha256`。APK、签名及私人截图不纳入 Git。源码、签名 APK 和公开验证摘要随 v0.6.0 发布。原始标注 ID 与 P1–P8 对应处理见 [实施记录](captures/appearance-20261029-194102/IMPLEMENTATION_LOG.md)。

发布前复核：配色、播放器工具、内置分类 Node 检查全部通过；截图批注 Python 测试 8 项通过；分类 Python 测试 53 项通过（1 项跳过）。本地截图与草稿链接仅供开发机审阅，公开发行不包含私人截图和设备序列号。
