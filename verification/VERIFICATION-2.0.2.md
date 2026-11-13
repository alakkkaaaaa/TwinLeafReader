# 大肥鱼阅读 2.0.2 验证记录

日期：2026-09-18。包名 `com.localreader.twinleaf`，版本代码 13。

## 安装包

- 文件：项目根目录 `大肥鱼阅读-2.0.2-debug.apk`，30,189,059 字节。
- SHA-256：`AC44F220C50F9DFC61C48E07CA743EFB115E901329EAE98BBAB13F7B4482B14B`。
- 签名证书 SHA-256：`29258a00d61f37a2f404652e2ac1462d747a0811dd17a238dabae2e298062b9e`，与旧版一致；已在专用模拟器上覆盖安装。
- 旧版安装包保留。请覆盖安装，不要先卸载；未改变原书文件和批注存储格式。

## 本轮修改

1. 根书架文件夹和图书共用混合顺序，均可长按拖动；旧版手动书序迁移，隐藏项目保持位置，移走后补齐。文件夹不支持嵌套。
2. 图书拖到文件夹中央移入；停在左右边缘则显示插入指示线并排序。文件夹拖到另一文件夹只调整位置。
3. 顶部 `＋` 合并导入和新建；`更多` 合并排序、筛选、批量管理，夹内另有文件夹设置。移除添加卡片和分散的重复入口，保留封面操作。
4. 亮暗按钮点击直接循环：跟随系统 → 始终浅色 → 始终深色 → 跟随系统，不弹菜单。阅读器与书架共用偏好；PDF 颜色参数仍独立保留。
5. PDF 浏览与书写模式均能从 1 倍直接双指缩放，不再要求先点击放大。范围 1–3 倍，双指中心锚定，缩回 1 倍归位。书写时单指不画线、不滚页；笔尖与手指混合或多余触点阻止缩放。

## 构建与验证

`assembleDebug`、`assembleDebugAndroidTest`、`testDebugUnitTest`、`lintDebug` 成功。

- 单元测试 57 项，0 失败：包括混合排序、隐藏槽位、主题循环、缩放锚点及上下限。
- Lint：0 错误、51 警告，非无警告构建。
- Android 15 / API 35 专用模拟器，3390 × 2400：最终两组界面回归分别 7 项、11 项全部通过，共 18 项。

第一组：

- `ReaderV2Test.pinchWorksFromOneInBrowseAndWritingWithoutZoomButton`：横屏浏览 1 倍缩放、横屏书写 1 倍缩放、竖屏书写 1 倍缩放、触笔与手指混合不缩放、纯手指不新增笔迹。
- `ReaderV2Test.canceledSecondaryStylusPointerDoesNotEraseOrSave`：系统取消次触笔指针时不保存或误擦除。
- `ReaderRegressionTest.folderLifecycleAndBooksPersist`：新建、成员、重命名、删除文件夹与书籍数据保留。
- `ReaderRegressionTest.importingInsideFolderAndReturningKeepsFolder`：夹内导入和退出阅读返回原文件夹。
- `ReaderRegressionTest.folderCoverPickerCropsRestoresAndPersists`：文件夹封面选择、裁剪、恢复及保存。
- `ReaderRegressionTest.pdfJumpShortcutsPersistAndToolbarIsVisible`：PDF 跳页、快捷页、工具栏及主题切换。
- `ReaderRegressionTest.epubFollowsSystemDarkWithoutLosingPosition`：EPUB 系统深色同步和阅读位置。

第二组：

- `ShelfDragTest` 全部 9 项：混合文件夹拖动、边缘排序与中央移入、夹内排序与移回根目录、筛选后的隐藏位置、取消拖动、旧顺序迁移、边缘自动滚动、移入后补齐、合并菜单与无弹窗主题循环。
- `ReaderV2Test.folderSortPersistsAcrossModesAndAppRecreation`：夹内排序模式及重建恢复。
- `ReaderV2Test.stylusInkPersistsHidesRotatesAndFollowsSystemDark`：笔迹保存、撤销重做、显隐、擦除、高亮、放大后的真实事件坐标、横竖屏和深色同步。

测试修正记录：竖屏模拟器使用居中兼容窗口，早期测试把视图全局坐标误当屏幕坐标，输入落入系统区域；已补上窗口屏幕偏移后重跑通过。此处为测试坐标修正，不通过放宽缩放断言回避问题。

截图：`v202-mixed-shelf.png`、`v202-add-menu.png`、`v202-more-menu.png`、`v202-writing-pinch.png`、`v202-writing-pinch-portrait.png`。已检查合并菜单和横竖屏缩放截图；缩放截图使用合成 PDF 测试页，非真实书页。

## 限制

未连接 OPPO Pad 4 Pro / OPPO Pencil 2 / ColorOS 16 实机。掌压接触是否被正确标记取决于设备驱动；两枚被系统认作普通手指的掌压点仍可能触发手势，不能承诺完全防误触。本轮未重跑所有历史功能（例如完整 PDF 导出）；此前记录见 `VERIFICATION-2.0.md`。
