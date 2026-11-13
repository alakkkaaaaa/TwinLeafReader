# 大肥鱼阅读 2.3.0 验证记录

验证日期：2026-09-24

## 实现范围

- 新增 `ReaderChromeStyle.kt`，为阅读顶部栏与批注栏提供同一组浅色／深色材质参数。
- `BookChrome` 改为 52dp 高、有外边距的圆角浮层；内容安全区由 48dp 调整为 64dp。
- `PdfInkToolbar`、普通动作按钮、工具分隔线和拟物笔具选中底座全部改为读取共享材质。
- `PdfToolActions` 的批注／显示设置图标使用统一对比色，激活时使用金色强调。
- 未增加概念图中的无实现按钮，所有可见按钮均连接现有功能。
- 共享材质读取 `LocalReaderDark`，跟随系统、固定浅色和固定深色模式均能触发 Compose 重组。

## 自动验证

- `:app:compileDebugKotlin`：通过。
- `:app:testDebugUnitTest`：通过，68 项测试、0 失败、0 错误。
- `:app:lintDebug`：通过，0 错误、43 个警告。
- `:app:assembleDebug`：通过，Gradle 总构建耗时 59 秒。

## 安装包核验

- 文件：`大肥鱼阅读-2.3.0-debug.apk`（30,191,709 字节）。
- 包名：`com.localreader.twinleaf`。
- 版本：`versionCode 17`、`versionName 2.3.0`。
- 应用标签：`大肥鱼阅读`。
- APK 签名：v2 校验通过；证书 SHA-256 为 `29258a00d61f37a2f404652e2ac1462d747a0811dd17a238dabae2e298062b9e`。
- 文件 SHA-256：`DB34AB51E51DDC333DDD0E03CCF66434D5A82D62FEA467F0D7D9A7524A7D10F2`。

## 实机验收重点

- 在“系统／浅色／深色”之间循环时，顶部栏和批注栏是否同时、即时变化。
- 横屏和竖屏中顶部栏是否完整，书名过长时是否正确省略，阅读内容是否不再进入栏体区域。
- 较窄竖屏中批注栏能否横向滚动到导出、翻页、缩放、诊断和保存状态按钮。
