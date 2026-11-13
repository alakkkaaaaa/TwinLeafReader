# 大肥鱼阅读 2.0 验证记录

验证日期：2026-09-17。版本名 2.0，versionCode 11，包名 com.localreader.twinleaf。

## 交付与安装

- 安装包：项目根目录 `大肥鱼阅读-2.0-debug.apk`，30,137,947 字节。
- APK SHA-256：`BA39DE4149301B73838204C5C2EB14E4A51E3AB97B7CB8961FAF7B7E38D4985B`。
- 签名证书 SHA-256：`29258a00d61f37a2f404652e2ac1462d747a0811dd17a238dabae2e298062b9e`，与 1.9.0 一致。
- 直接覆盖安装，不要先卸载。卸载或清除应用数据会删除书架记录与应用内部批注。

## 本次功能

1. PDF 独立矢量批注层：笔、荧光笔、整笔擦除、颜色／粗细、撤销／重做、自动保存和一键显隐。EPUB 不增加批注。
2. 书写模式只接受系统识别的触笔／橡皮输入；阅读区域手指和手掌不画线、不滚动、不翻页。浏览模式可移动页面；支持放大后书写。
3. 原文与批注合并另存为 PDF，保留原文字／图形，不覆盖原书。导出使用原始配色，可选择包含批注或仅原文，软件内笔迹保持可编辑。
4. 跟随系统或固定浅色／深色；书架、阅读界面、EPUB 同步响应。PDF 支持柔和／反色、强度和保留原色。
5. 文件夹独立排序：手动拖动／箭头、书名自然排序、导入时间、最近阅读及升降序。自动排序保留原手动顺序。

## 构建与自动验证

- `assembleDebug`、`assembleDebugAndroidTest`、`testDebugUnitTest`、`lintDebug` 离线构建成功。
- 单元测试 44 项通过，0 失败。
- Lint 0 错误、48 警告；不代表无警告构建。
- Android 15 / API 35 专用模拟器；横屏 3390×2400，竖屏 2400×3390，density 320。
- 14 项不同设备测试通过，分批运行；连续翻页测试末轮另行复测通过。不是在 OPPO 实体平板上执行。

设备测试清单：

1. `ReaderV2Test.hiddenInkExportsThroughSystemPickerAcrossRotation`：隐藏笔迹后仍可按导出选项包含批注；真实系统保存选择器和方向切换；导出成功且保留显隐状态。
2. `ReaderV2Test.exportPreservesTextAndCropRotationAndNeverChangesSource`：四种旋转／裁剪页面的文字保留、笔迹位置、原文件不变、仅原文逐字节复制、拒绝覆盖源文件。
3. `ReaderV2Test.folderSortPersistsAcrossModesAndAppRecreation`：自然排序、自动／手动切换、箭头与实际拖动、重建后的顺序恢复。
4. `ReaderV2Test.stylusInkPersistsHidesRotatesAndFollowsSystemDark`：手指拒绝、触笔取消、保存、撤销／重做、擦除、荧光笔、隐藏／显示、缩放后实际系统注入笔输入、旋转及系统深色响应。
5. `ReaderV2Test.canceledSecondaryStylusPointerDoesNotEraseOrSave`：非首指针触笔与取消标记处理。
6. `ReaderRegressionTest.epubFollowsSystemDarkWithoutLosingPosition`：EPUB 跟随系统深色、位置保持、无批注工具栏。
7. `ReaderRegressionTest.portraitPdfScrollsAndRotatesWithoutCurl`：PDF 竖屏滚动及方向切换。
8. `ReaderRegressionTest.consecutiveTurnsReusePreparedPages`：连续翻页复用预备页面。
9. `ReaderRegressionTest.importOutlineThroughPickerPreservesTreeAndRejectsBadInput`：大纲文件选择导入、层级与坏输入拒绝。
10. `ReaderRegressionTest.pdfJumpShortcutsPersistAndToolbarIsVisible`：PDF 跳页、快捷页持久化及工具栏可见。
11. `ReaderRegressionTest.folderCoverPickerCropsRestoresAndPersists`：文件夹封面选择、裁剪、恢复与保存。
12. `ReaderRegressionTest.epubChapterPageJumpAndCrossOrientationShortcuts`：EPUB 章节内跳页及跨方向快捷页。
13. `InkPersistenceRestartTest.savedStrokesLoadInANewProcess`：独立测试进程中从磁盘重新读取之前保存的笔迹。
14. `ReaderRegressionTest.pdfCanExitWhilePageIsTurning`：PDF 翻页中退出。

## PDF 与界面目视检查

测试 PDF 为自行生成的四页样本，含文字、裁剪框和 0／90／180／270 度旋转。导出后用独立 PDF 工具提取文字，并用 Poppler 渲染四页检查：原文字保留，红色笔迹在各旋转页面相应位置，未整页栅格化。

已检查的截图和渲染文件位于本目录：

- `v200-ink-landscape.png`、`v200-ink-portrait.png`：横竖屏笔迹及工具栏。
- `v200-zoom-ink.png`：放大后笔迹坐标。
- `v200-pdf-system-dark.png`、`v200-epub-system-dark.png`、`v200-shelf-system-dark.png`：系统深色模式。
- `v200-folder-sort.png`：文件夹排序。
- `v200-export-success.png`：真实保存流程成功反馈。
- `v200-rotated-ink.pdf`、`v200-export-check-1.png` 至 `v200-export-check-4.png`：旋转／裁剪导出测试样本与渲染。

## 已知边界与实机验收

- 尚未连接 OPPO Pad 4 Pro / Pencil 2 / ColorOS 16；真实书写延迟、压力曲线和厂商掌压识别需实机验收。未承诺触笔私有按键或悬浮功能。
- 批注保存在应用内部独立文件中，原书不变；移出书架保留批注，同一 URI 再导入可恢复。请等待“已保存”再强制结束应用；卸载前导出重要笔记。
- 合并导出的笔迹是 PDF 页面矢量内容，不是其他软件可单独编辑的标准批注对象。带数字签名或禁止修改的文件不执行批注合并。
- 深色算法也会改变图片颜色，不进行文字／照片智能分离；辨色场景使用原色。导出始终采用原色。
- 大型扫描书、大量长期笔迹的性能和极低剩余空间尚未压力测试。导出请留足空间并保持应用前台；系统终止进程可能中断输出。
- 放大缩放现有页面纹理，不提升扫描件源分辨率。手动拖动排序无长按自动滚屏，可滚动列表后再移动或用箭头微调。
- 旧版未保存导入时间，迁移时用已有最后阅读时间近似，不保证还原真实历史导入时间。

