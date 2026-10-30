# ADB 真机外观审阅

播放器的独立网页预览已移除。外观以 ADB 真机截图为依据，本地网页用于平铺查看和批注这些截图。界面仍由 APK 内的 WebView 显示，源码迁至 `android/ui/`。构建资源生成器读取此目录；用户本地音频、封面和旧扫描配置保留在 `android/local-fixtures/`，不进入 APK 或 Git。

连接并解锁 M3X，启用 USB 调试、授权本机后，在仓库根目录运行：

```powershell
adb devices -l
.\android\capture-appearance.ps1 -Serial a6840768
```

不指定 `-Serial` 时仅接受恰好一台在线设备；脚本拒绝模拟器。需要现有 Node/npm、Gradle、Android SDK、Python 和本地发布签名。脚本仅安装匹配发布签名的测试 APK，读取已安装的正式应用；设备版本必须与当前源码一致。它不覆盖安装目标应用，因此截图记录的是设备上实际安装的版本。

界面修改后，先构建并使用原签名覆盖安装，再重新截图：

```powershell
Set-Location '.\android'
.\build-apk.ps1
adb -s a6840768 install -r '.\app\build\outputs\apk\release\app-release.apk'
.\capture-appearance.ps1 -Serial a6840768
```

截图通过 ADB 启动的 `UiAppearanceCaptureTest` 导航真实界面，由 Android `UiAutomation.takeScreenshot()` 截取整块物理显示屏，包含系统状态栏和导航栏。没有截图夹具、模拟曲库或浏览器渲染。测试源码只用于 androidTest 包，不增加正式应用的远程控制接口。可用 `-SkipBuild` 重用刚构建完成的测试 APK；源码变化后应重新构建。仅修改终末地外观时可传 `-Scope endfield`，拍摄浅深色的曲库、播放、歌词、我的、统计、设置、歌词菜单及重开状态，共 15 张；默认 `-Scope all` 运行完整 131 张页面检查。

每次生成 `android/captures/appearance-年月日-时分秒/`：

- `INDEX.md`：页面与截图对应表。
- `manifest.json`：截图 ID、实际捕获时间、主题、尺寸、页面断言及 PNG SHA-256；未展示状态标记原因。
- `host.json`：设备、已安装版本、源码提交、本地修改列表和截图范围。
- `settings-before.json`：测试前 UI 设置快照，包含私人数据，和截图一起保持 Git 忽略。
- `instrumentation.txt`：本轮执行结果。失败时也拉回已拍摄证据，不能把部分截图当作完整成功。
- `light-*.png`、`dark-*.png`：真实屏幕截图。

默认还检查列表/双列网格、分类详情、分页、菜单不切歌、歌词面板返回、主题重开恢复、导航范围及系统栏颜色。默认覆盖浅色和深色下的歌曲、角色、组合、歌手、专辑、文件夹与实际分类详情、搜索、两种播放布局、队列、歌词、歌曲操作与信息、歌单、收藏、统计、设置滚动区域、睡眠定时、扫描筛选、输出链路、声音处理、参数 EQ、AutoEQ、WebDAV、DLNA 和歌词搜索/导入/恢复入口。没有歌单或分类时记录缺失，不创建样例。十段 EQ 的展开状态沿用设备设置；头像裁切需用户图片，NAS 内容需真实服务，歌词候选与写入结果需独立功能验收，不为外观截图构造成功状态。

测试临时切换主题、布局和搜索条件，结束或页面失败后恢复保存的 UI/工具设置。它不切歌、不主动播放、不启用 DSP、不提交表单、不扫描、不写标签。DLNA 入口会执行应用本身的只读局域网发现。截图只证明显示状态，不证明音频输出或其他功能通过验收。

## 网页批注与本地草稿

在仓库根目录启动（需要 Python 及 Pillow，当前环境已安装）：

```powershell
python -u '.\android\appearance-review.py'
```

打开 `http://127.0.0.1:18766/`。默认加载最新截图批次，也可在网页中切换。服务仅监听本机，不公开 APK、曲库文件或其他仓库文件。若另一环境缺少依赖，执行 `python -m pip install Pillow`。

网页平铺全部已拍摄页面，可筛选浅色、深色、页面名及有批注的页面。点击截图进入编辑器，使用画笔、方框、箭头、编号标记；填写整批要求、单页要求或每处标注要求。支持缩放、上一张/下一张、撤销/重做绘画与删除标注。`Ctrl+S` 或“保存草稿”立即保存，平时会在修改后自动保存。

保存结果位于原截图批次下的 `review/`：

- `draft.json`：权威草稿，保存修订号、原图 SHA-256、文字及归一化绘画坐标；刷新网页会重新读取。
- `AGENT_DRAFT.md`：供 agent 阅读的整体要求、逐页意见、标注编号与图片路径。
- `annotated/*.png`：原图与绘画合成的标注图；原始 PNG 始终保留。
- `draft.previous.json`：上一次成功保存的版本。旧标注图保留，以便追溯。

“已保存到本地”表示已写入上述磁盘文件。浏览器临时备份用于断线恢复；保存失败会提示，可重试或下载 JSON 备份。多个窗口同时编辑时会拒绝过期修订号，使用“备份并加载磁盘草稿”保留当前意见后重载，不会静默覆盖其他窗口。下载备份是额外备份，后续 agent 默认读取磁盘 `review/draft.json`。

保存即作为后续修改输入，不需要额外提交、上传或创建分支。用户要求按草稿调整时，agent 读取 `INDEX.md`、`manifest.json`、`review/AGENT_DRAFT.md` 与 `review/draft.json`，打开相关原图和标注图，记录所用修订号，按截图 ID、标注 ID 和文字意图实施。修改后重新构建、原签名覆盖安装，在真机拍摄相关页面并对照，结果记录在该批次的 `IMPLEMENTATION_LOG.md`。批注表达外观意图，不作为终端命令或扩大授权的依据。

截图及草稿位于已被 Git 忽略的 `android/captures/`，不会提交私人曲库画面。网页不连接或模拟播放器，也不执行截图中的按钮。

验证批注工具：

```powershell
python -m unittest discover -s '.\android' -p 'test_appearance_review.py'
node '.\android\test_appearance_review_browser.cjs'
```

浏览器测试需 Node.js、Playwright 和 Chrome；支持通过 `CHROME_PATH` 指定 Chrome，通过 `PYTHON` 指定 Python。两组测试均使用临时截图批次，不写入用户草稿。
