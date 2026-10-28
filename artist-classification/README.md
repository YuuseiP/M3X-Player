# IMAS 歌手分类：模型语义决定 + 本地只读预览

当前入口是已保存的语义分类配置，不再使用符号拆分、CV 正则、名字前缀等算法判断身份。DeepSeek 4.1 Flash 和 GPT-6 Luna（NexaVlinks）逐项阅读原始署名，疑难项结合搜索核实；模型决定保存在 JSON。汇编脚本只做精确查表、完整性校验和歌曲关联，不负责识别人物。

本阶段不修改 APK、音乐文件、标签、文件名或原始扫描报告。仅绑定 IMAS 文件夹，其他文件夹不应用这些分类。

## 配置与结果

- `folder-bindings.json`：文件夹和语义配置的关系，当前为 `D:\BaiduNetdiskDownload\THE_IDOLMASTER` → `semantic/profile.json`。按 Windows 路径组件匹配并覆盖子目录，不会误匹配 `_backup`；多个绑定取最深目录。
- `semantic/profile.json`：配置 ID、精确查表引擎、目录文件和各批次使用的模型。
- `semantic/decisions/batch-01.json` 至 `batch-09.json`：各模型的逐项决定，包含 ID、身份、状态、理由、实际参考来源。输入批次位于已忽略的 `semantic/input/`，来自本机扫描快照。
- `semantic/credits.json`：校验通过后生成的完整精确查表配置；保存原文、字段用途、分类、理由、模型和来源。可以审计，不依赖旧角色名单。
- `semantic/consistency-review.json`：37 条冲突候选的定向复核记录，含实际修改及保留疑义的条目；不是全库二次核实的声明。
- `semantic/encyclopedia-names.json`：按身份类别和规范原名精确匹配的百科汉字名及实际参考来源；未确认项另列，不按读音自行翻译。汇编时应用，不覆盖原批次判断。
- `output/classification.json`：网页使用的歌曲索引，包含全部原始记录、角色/组合/其他署名/专辑艺术家关联和逐项语义依据。
- `output/semantic-report.md`：本次语义结果统计与仍有疑义的署名。

每个原始署名必须有模型决定。新增/变动标签或更换快照后不能直接套旧名单：必须先补充或重新确认语义决定；汇编器在缺项、重复、错误来源摘要、无效结构时停止，**没有旧分类算法兜底**。文件夹移动也要同步新快照和绑定。

`resolved` 表示模型已作出明确判断，不代表每项均经官方资料核实；`unresolved` 明确保留疑义及原文；`placeholder` 不生成虚构人物。所有字段均有决定，但只有 ARTIST 参与歌曲演唱者分类：ALBUMARTIST 仍是独立专辑艺术家维度，PERFORMER、ENSEMBLE、排序字段保留语义解释，不用于补写缺失演唱者。

## 分类口径

1. 同一角色的 CV、空白和明确别名差异归到同一角色，不创建 CV 分类；单独出现声优名时不反推角色。明确的“声优（角色役）”归所写角色。
2. 多人署名由模型逐项理解，分别关联到每个人；同一首歌在每个角色下都能找到，每个分类内只出现一次。
3. 真正的组合或集合署名直接保留；不凭团名展开成员，只有原始署名明确写出成员时才建立对应个人关联。合法个人名中的 `/` 等符号不硬拆。
4. 用户确认 UNION!! 尾部截断的“白石 ”归到白石紬；其他纠错以具体语义证据为准，不做通用前缀补全。
5. 原始值完整保留，缺失或不可读标签不编造。分类名优先采用百科收录的汉字名；没有确认汉字名的保留原名，不直接翻译或凭读音造名。歌曲、原始署名及原模型批次判断不改，百科名称变更及来源在“文件与分类依据”中单独展示。

## 生成与检查

仅需 Python 标准库。在项目根目录执行：

```powershell
$env:PYTHONIOENCODING = 'utf-8'
$env:PYTHONDONTWRITEBYTECODE = '1'
python 'D:\CodeFile\M3X-Player\artist-classification\assemble_semantic.py' 'D:\CodeFile\M3X-Player\歌手问题汇报_2026-10-26\歌手标签扫描明细.json'
python -m unittest discover -s 'D:\CodeFile\M3X-Player\artist-classification' -p 'test_*.py' -v
```

完整批次通过校验后才发布查表配置和网页索引；不完整的模型返回不能当作成功结果。检查包括全部原始标签覆盖、批次互斥、ID 唯一、字段隔离、源记录逐项不变、路径不越界以及歌曲分类正反索引一致。源文件字节摘要须与分批输入一致。

`classify_artists.py`、`rules.json` 和 `profile-decisions.json` 是旧算法及其历史资料，仅保留用于回归对照；当前绑定不再使用它们。旧的 `classification-report.md`、`signature-review.md`、`确认报告.md` 等报告也不是本轮语义结果，请以 `semantic-report.md` 为准。

## 网页分类试听

```powershell
python 'D:\CodeFile\M3X-Player\artist-classification\preview_server.py'
```

打开 `http://127.0.0.1:18767/`（语义版新地址；原 18766 进程仍可能保留旧快照）。角色、组合/集合、其他署名和原始署名可分别浏览；支持来源子文件夹筛选、曲目搜索、复核项筛选、多人歌曲跳转和真实本地歌曲试听。展开“文件与分类依据”可见初判模型、定向复核模型、理由及适用的检索来源。页面底部显示当前是否使用语义索引、处理署名数及疑义数。

分类切换不打断播放，队列沿用开始播放时的筛选结果，到末尾停止。不自动播放、不做转码；浏览器不支持的格式会提示失败。文件不可用时仍保留分类浏览并禁用播放。

服务启动时读取已保存索引，不重新扫描或调用模型，更新结果后需重启服务。只监听本机，不提供写入接口；音频只能通过索引内的歌曲 ID 读取，并拒绝目录越界和根目录外的符号链接。

`test_preview.cjs` 使用本机 Chrome/Playwright 验证完整本地曲库、语义来源、截图所示多人署名、UNION!! 截断修正、角色交叉归属、组合保留、FLAC 播放/拖动及 320～1440px 布局。截图写入已忽略的 `output/preview/`。缺少个人曲库/输入快照时，全量回归需要本机补齐素材。
