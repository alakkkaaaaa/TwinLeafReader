# 大肥鱼阅读 3.0.3 验证记录

日期：2026-10-26。

## 用户反馈与已知限制

3.0.2 实机反馈：方向键正常，触控板仍只能翻一次。设备为 OPPO Pad 4 Pro 官方智能触控键盘。代码中确认上一版缺少 Activity.dispatchTouchEvent 路径，且原生双指处理只接受 SOURCE_TOUCHPAD。Android 官方说明触控板 MotionEvent 通常返回 SOURCE_MOUSE。

参考：https://developer.android.com/reference/android/view/InputDevice#SOURCE_TOUCHPAD

这证明存在兼容缺口，但尚无用户实体键盘事件采样，不能断言其故障已在实机复现或解决。

## 改动

1. 触摸与通用运动分发均接入外部滚动识别。只接管鼠标／触控板来源、明确双指分类或滚动轴的手势，触屏与触笔不参与。
2. 支持坐标滑动兼容流、原生像素增量和历史样本；首次未分类 DOWN 可以继续点击，后续识别为滚动时先向原子视图发送 CANCEL，再翻页。
3. 接管后持续持有该设备／downTime 手势到 UP / CANCEL，即使末事件无分类、无轴或坐标越界也清理；普通点击、新捏合与从工具栏开始的滑动不被抢走。
4. 三种原生翻页视图在动画期间也释放 UP / CANCEL，避免终止事件被动画短路吞掉。原有方向键映射不变。

## 构建与验证

- `testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug --offline` 成功。
- JVM：141 项，0 failures、0 errors。
- Lint：0 errors、91 warnings、1 hint。
- Android 15，3390×2400 模拟器：ReaderInputRouterTest 8 项、ReaderExternalNavigationTest 4 项、StylusBoundaryRegressionTest 7 项最终通过。
- 新回归覆盖 10 次连续鼠标来源的分类滑动、失去分类且越界的 UP、点击转滚动前取消、像素轴、工具栏起点、禁用、捏合与触屏隔离。
- PDF / EPUB 单页卡片及双页卷页各验证滚轮后接坐标型触控板滑动、再接像素型滑动，实际阅读位置更新，再用方向键回翻；连续阅读和已有笔迹边界通过。
- 首轮 19 项中 18 项通过。旧捏合测试夹具在滚动 MOVE 后未发 UP，就以同一 downTime 切换成捏合；新逻辑为防止已取消的子视图收到半截手势，会消费这个流而不翻页。夹具补上 UP，并以新 downTime 开始新的捏合后单项复测通过。
- 原始日志：`v303-touchpad-tests.log`；复测：`v303-terminal-retest.log`。没有对首轮失败进行覆盖隐藏。

## APK

- 大肥鱼阅读-3.0.3-debug.apk，versionCode 33，31,528,495 字节。
- SHA-256：`136ebe59b6ac591e488249aab03fb313cb4e8f2c8e7704f0884f6a0bf3396c67`。
- 签名验证通过；证书 SHA-256：`29258a00d61f37a2f404652e2ac1462d747a0811dd17a238dabae2e298062b9e`，与上一版一致。

请在实体键盘上验证不先点击直接滚动、连续往返翻页、停顿后再次滚动和点击后再滚动。如果仍失败，下一步需要采集该设备的实际事件来源、动作、分类及滚动轴，避免继续仅凭模拟事件推断。
