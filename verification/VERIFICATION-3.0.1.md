# 大肥鱼阅读 3.0.1 验证记录

验证日期：2026-10-10。

## 构建与安装包

- `versionName=3.0.1`，`versionCode=31`，Debug 构建，minSdk 26，target/compileSdk 37。
- 执行 `testDebugUnitTest assembleDebug assembleDebugAndroidTest --offline`：BUILD SUCCESSFUL。
- 136 项 JVM 单元测试通过，0 failures，0 errors；新增 4 项预测与分块边界测试。
- `lintDebug --offline`：BUILD SUCCESSFUL，0 errors、88 warnings、1 hint；仍有项目既有的绘制分配、可访问性和依赖提示，并非零警告构建。
- 安装包：`大肥鱼阅读-3.0.1-debug.apk`，31,512,052 字节。
- SHA-256：`c61eb866f5a96f98a4a163d000214bfca774df0a83d2abc888be895920408d82`。
- 与 3.0.0 APK 的签名证书 SHA-256 一致：`29258a00d61f37a2f404652e2ac1462d747a0811dd17a238dabae2e298062b9e`；可以覆盖安装，不需要清空本机数据。

## Android 专项验证

设备：TwinLeafVerification，Android 15 模拟器，3390×2400。使用合成手写笔事件及绘制断言，不是实体 OPPO 手写笔测试。

执行四个测试类，输出记录为 `v301-ink-instrumentation.log`，结果 `OK (13 tests)`：

- `LiveInkRendererTest`（2）：5000 点普通长笔画首次录制 4999 段；重复绘制不重录；追加一个采样点只重录变化尾块，增量不超过 128 段；清理后不继承旧笔数据。软件回退及持续增长的荧光笔与完整保存笔迹像素一致。
- `StylusBoundaryRegressionTest`（7）：PDF 与无限笔记均验证悬停/孤立 MOVE、新落笔、缺失指针、downTime 更换、不同设备、取消、零压力及漂移 UP 坐标等边界。
- `HistoricalInkCacheTest`（3）：历史追加、荧光笔透明度、橡皮、撤销、主题与缩放、3390×2400 密集视口缓存保持。
- `InkPersistenceRestartTest`（1）：保存的笔迹可以重新加载。

JVM 新增验证：预测距离与刷新率/缩放限制、预测不修改源点、急转/反向/压力骤降/速度突增/过期输入拒绝预测，分块覆盖 0—50,000 点且每个真实线段恰好覆盖一次。

上述 128 段是 CPU 绘制指令重录工作量限制，不是帧率或毫秒延迟指标。测试验证结构与行为，并没有测量真实屏幕扫描或 Pencil 2 驱动延迟。

## 实机验收建议

覆盖安装后，在相同笔设置下比较 PDF 与无限笔记：快速小字、长直线、转角、停笔点、慢速轻写与跨区域反复抬笔。确认没有临时预测尾端被保存，旧笔可以擦除/撤销，缩放后笔画与纸张对齐。如果仍有连笔，请在触笔校准入口采集设备实际压力/距离，不套用便签中的未知单位阈值。
