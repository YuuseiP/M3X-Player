# 山灵 M3X 配置

整理日期：2026-10-25。

适用范围：此前讨论的山灵 M3X 原版（2GB RAM + 32GB 存储），不适用于 M3 Ultra；尚未核对实际设备的版本与固件。

## 系统与硬件

以下为山灵官方公布的参数。[1]

| 项目 | 配置 |
| --- | --- |
| 操作系统 | 开放式 Android 7.1 |
| 音频系统 | AGLO（Android Global Lossless Output） |
| 处理器 | 高通骁龙 430，八核 |
| 运行内存 | 2GB |
| 内置存储 | 32GB |
| 存储扩展 | microSD 卡槽 |
| 屏幕 | 4.2 英寸触摸屏，1280 × 768 |
| DAC / 耳放芯片 | 双 ESS ES9219C |
| 耳机接口 | 3.5mm 单端、4.4mm 平衡 |
| USB 接口 | USB-C |
| Wi-Fi | 2.4GHz / 5GHz |
| 蓝牙发射编码 | LDAC、LHDC、aptX HD、aptX、SBC |
| 蓝牙接收编码 | LDAC、SBC |
| 电池 | 3200mAh |
| 尺寸 | 109 × 72 × 15.9mm |
| 重量 | 168g |

## 音频能力

以下是设备规格，并非每个第三方播放器都能实现的输出能力。[1]

| 项目 | 官方规格 |
| --- | --- |
| PCM | 最高 32bit / 384kHz |
| DSD | 最高 DSD256 |
| MQA | 支持 16× 展开 |
| 平衡输出功率 | 240mW @ 32Ω |
| 输出阻抗 | < 1Ω |
| USB 功能 | 支持 USB DAC 与 USB 数字音频输出 |

## 标称续航

| 使用模式 | 官方标称续航 |
| --- | --- |
| 单 DAC、单端输出 | 最长 23 小时 |
| 双 DAC、单端输出 | 最长 20 小时 |
| 平衡输出 | 最长 19 小时 |

上述为官方标称值，不代表当前电池状态下的实测结果。[1]

## 固件与软件适配

- 截至整理日期，山灵官方下载页列出的 M3X 固件为 **V1.75**，发布日期为 **2024-01-19**；不能据此认定本机已经安装该版本。[2]
- Android 7.1 对应 **API 25**。面向这台设备的应用，其 `minSdk` 不应高于 25；满足这个条件只是安装兼容性的必要条件，不代表运行和音频输出已经验证。[3]
- 模拟器可用于查看界面和一般功能，但不能代替 M3X 真机验证内置 DAC、实体按键、采样率切换与续航。
- 不将“支持读取某种音频文件”等同于“支持该格式的原生输出”，也不将设备的 AGLO 标识直接作为任意第三方应用实现 bit-perfect 的验证结果。

## 资料来源

参数以山灵官方资料为依据；音频输出和续航尚未进行真机测试。

1. 山灵官方英文介绍：Shanling M3X — Portable Hi-Fi Android Player  
   `https://en.shanling.com/article-M3XIntro.html`
2. 山灵 M3X 官方固件下载页  
   `https://shanling.com/download/81`
3. Android 官方版本与 API 对照：Codenames, tags, and build numbers  
   `https://source.android.com/docs/setup/reference/build-numbers`
