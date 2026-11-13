# 大肥鱼阅读 1.8.0 验证

2026-09-16；包名 `com.localreader.twinleaf`；versionCode 9。

## 本次变更

- 自定义大纲支持 1–12 级、折叠、搜索、当前项标记；每本书保存折叠状态和 EPUB 目录页签选择。旧平面大纲迁移为一级，保持条目顺序。
- PDF 支持 UTF-8 TXT 与 JSON 外部大纲，经校验、预览后合并或替换自定义大纲。合并按父级匹配，重复导入不重复添加；无效输入不部分写入。文件上限 1 MB、5000 条；TXT 三列为层级、实际页码、标题，以 TAB 分隔。
- EPUB 解析 NAV / NCX 的目录层级和章节锚点，横竖屏均支持段落定位。原书目录只读，自定义大纲独立；没有可用目录时退回按正文文件生成的简单目录。外部链接和自定义 XML 实体不加载。
- 书架新增教科书、小说、漫画、未分类及全部筛选；导入前选择分类，长按或更多菜单管理，支持批量分类。
- 封面支持本地图片与指定 PDF 页，预览、缩放、平移裁剪和恢复原封面。保存为应用内独立 720×1000 JPEG，不修改原书。
- 兼容旧书架、进度、快捷页；分类和换封面不重置阅读进度。

## 构建与静态检查

`testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest` 成功。

- 32 项单元测试通过，0 failures、0 errors。
- Lint：0 errors、34 warnings；警告仍在，未宣称零警告。
- 交付 APK 与最终构建 APK 的 SHA-256 一致。

## 设备回归

专用 Android 15 / API 35 模拟器，density 320；横屏 3390×2400、竖屏 2400×3390。

以下 16 项不同运行测试在本次迭代中通过；包含分组重跑，不将重复执行计为新测试。最终一组 `Time: 96.735`、`OK (4 tests)`。

1. `customPdfOutlineEditsPersistsAndJumpsInPortrait`：PDF 大纲增改、保存、竖屏跳转与删除。
2. `importOutlineThroughPickerPreservesTreeAndRejectsBadInput`：实际系统文件选择器导入 TXT、树层级与折叠恢复、跳转、非法页码拒绝、合并去重、JSON 替换。
3. `builtinTocParsesNavAndNcxAndJumpsToFragment`：NAV 与 NCX 层级、排除外部链接、指定段落锚点的横竖屏跳转；竖屏目标距视口顶部小于 4 CSS 像素。
4. `coverReplacementAndCategoriesPersistWithoutChangingProgress`：图片封面裁剪、720×1000 输出与颜色像素验证、恢复原封面、PDF 页封面、分类与批量分类、重建后持久化且进度不变。
5. `legacyDataMigratesAndCoverCropStaysInsideImage`：旧书架及平面大纲迁移、每书隔离、删除清理、裁剪边界。
6. `epubOutlineCanJumpToOtherChapterInBothOrientations`：自定义 EPUB 大纲跨章跳转及横竖屏目录页签保留。
7. `consecutiveTurnsReusePreparedPages`：PDF、EPUB 连续翻页的预备帧复用。
8. `redesignedShelfAndSymbolSettings`：书架与符号设置入口。
9. `removeFromShelfKeepsOriginalAndSurvivesRecreation`：移出书架、保留原文件、重建后状态。
10. `epubOpensPaginatesTurnsAndReturns`：EPUB 打开、分页、翻页、恢复与返回。
11. `pdfCanExitWhilePageIsTurning`：PDF 翻页过程中退出。
12. `portraitPdfScrollsAndRotatesWithoutCurl`：PDF 竖屏滚动与横竖屏切换。
13. `portraitEpubScrollsCrossesChaptersAndRestoresLandscape`：EPUB 竖屏滚动、跨章与恢复横屏。
14. `allLocalEpubSamplesRenderInActualWebView`：已暂存的 10 本真实 EPUB 在实际 WebView 中打开并核对首屏文字／图像像素。
15. `pdfJumpShortcutsPersistAndToolbarIsVisible`：PDF 页码跳转、快捷页持久化与工具栏可见性。
16. `epubChapterPageJumpAndCrossOrientationShortcuts`：EPUB 章节内页码跳转及横竖屏快捷页。

测试开发中遇到的 XML 特性兼容及系统选择器操作问题已修复并重跑。最终封面测试额外断言放大和平移后的实际输出像素；目录导入测试包含真实 JSON 文件选择及替换操作，未以仅解析测试代替。

## 视觉核对

- `v180-imported-tree.png`：最终目录缩进、折叠箭头及编辑删除入口。
- `v180-custom-cover-shelf.png`：自定义封面、分类栏与书架。
- `v180-cover-crop-editor.png`：放大及水平平移后的裁剪预览，与保存结果一致。
- `v180-toc-landscape-anchor.png`、`v180-toc-portrait-anchor.png`：原书目录锚点跳转。

以上截图已读取核对；红色封面来自专用双色测试图片，不是应用默认封面。

## 使用边界

- 外部 PDF 大纲填写包含封面等页面在内的实际文件页码，从 1 开始。印刷页码的偏移量应在生成 TXT / JSON 时加好，导入时不会再加一次。
- EPUB 原书目录使用锚点，缺失锚点时退到相应章首；自定义数字页码仍随当前章节版式解释，不能保证更换字号后对应同一段文字。
- 测试覆盖合成书与 10 本真实 EPUB 首屏，不代表所有书的全部章节、加密格式或所有实体设备已验证。
- 本次没有测量实体平板帧率，不作零卡顿或性能百分比承诺。
- 分类、封面、大纲均本地保存；移出书架不删除原书，但会清理该书大纲和快捷页。覆盖升级前不要卸载。

## 安装包

- 文件：`大肥鱼阅读-1.8.0-debug.apk`，20,643,220 字节。
- SHA-256：`766A8A393B29F82ED48286B0D3D8AD7E9368BA1D845CF298B2935A5119369CE7`。
- APK 签名验证通过；证书 SHA-256：`29258a00d61f37a2f404652e2ac1462d747a0811dd17a238dabae2e298062b9e`，与旧版相同。
