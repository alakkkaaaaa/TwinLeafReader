# 大肥鱼阅读 3.0.2 验证记录

验证日期：2026-10-26。

## 构建与安装包

- versionName 3.0.2，versionCode 32，minSdk 26，compile/targetSdk 37。
- `testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug --offline` 构建成功。
- 141 项 JVM 测试通过，0 failures、0 errors；其中新增 ScrollPageGateTest 5 项。
- Lint：0 errors、90 warnings、1 hint；不是零警告构建。
- 安装包：`大肥鱼阅读-3.0.2-debug.apk`，31,682,836 字节。
- SHA-256：`08c983180366de3985aba25a118329beda5c3a866218c3113527e19c84407650`。
- APK 签名验证通过，证书 SHA-256：`29258a00d61f37a2f404652e2ac1462d747a0811dd17a238dabae2e298062b9e`，与 3.0.1 一致，可覆盖安装。

## 输入实现与边界

- Activity 统一接收阅读区域外部输入，阅读视口按生命周期注册和解除，不依赖点击后焦点；输入框保留方向键编辑功能。
- 标准 ACTION_SCROLL 使用系统水平／竖直滚动比例；Android 14+ SOURCE_TOUCHPAD 原生滚动距离按像素处理，包含历史采样，不重复乘滚轮倍率；捏合和触笔事件不作为翻页。
- 分页滚动阈值 48dp，持续翻页最短尝试间隔 220ms，静默 180ms 后按新手势累计；反向或主轴变化重置累计。动画忙碌的请求丢弃，不排队。
- 方向键首次按下立即响应，长按重复最短间隔 120ms；页面边界不把焦点移到工具栏。设置、目录、PDF 书写／放大等状态暂停外部阅读输入。
- 修正 PDF / EPUB 卡片动画先 start 后赋值的时序：先保存 animator 再 start，避免零时长完成回调后留下过期引用。

## Android 专项验证

设备：TwinLeafVerification，Android 15 模拟器，3390×2400。使用合成标准输入事件，不是用户实体触控板测试。

- ReaderInputRouterTest：5 项通过。重复滚动、反向、边界、禁用、输入修饰键、触笔隔离、六种导航键、连续滚动、目标解除和 Android 14+ 原生触控板历史像素增量。
- ReaderExternalNavigationTest：4 项最终通过。实际 PDF / EPUB 单页卡片和双页卷页中连续三次滚动均更新阅读位置；方向键回翻；两种连续阅读反复滚动和方向键；禁用视口、编辑框和退出阅读清理。
- StylusBoundaryRegressionTest：7 项通过，已有收笔边界未受外部输入改动影响。
- 首次 16 项运行中 15 项通过，退出阅读清理测试因 setContent 后仅 waitForIdleSync、尚未等待 Compose 提交而失败。测试改用 SideEffect 提交标记等待，再重新编译测试 APK；该项复测通过，未为此修改生产代码。
- 原始记录：`v302-external-input-tests.log`；单项复测：`v302-disposal-retest.log`。16 项最终均通过，不把首次失败隐去。

## 实机验收

覆盖安装后，PDF 和 EPUB 各验证：不点击页面直接双指滚动、连续往返十次、停顿后再次滚动、单双页及连续模式切换、方向键与长按、打开设置／输入框后不误翻页。滚动方向遵从设备报告的方向与系统自然滚动设置。尚未直接读取用户键盘的厂商事件，也未测量实体触控板延迟。
