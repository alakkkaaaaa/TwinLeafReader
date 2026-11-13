# 大肥鱼阅读 1.9.0 验证

2026-09-17；包名 `com.localreader.twinleaf`；versionCode 10。

## 本次范围

- 启动图标改为 AdaptiveIconDrawable；保留原插画，以资源布局裁去固有圆角外边，由桌面遮罩决定最终形状。
- 书架标题与空书架图标同步改为1.32倍铺满裁切，避免继续显示旧图边框。未生成或替换人物插画。
- 单层系列文件夹：新建、重命名、空文件夹、重复名称校验、单本／批量移动、跨分类整理成员、返回根书架、删除确认。
- 文件夹内导入自动归入当前文件夹，退出阅读仍返回该文件夹；界面重建保存当前文件夹。
- 文件夹默认最多四本书封面拼图，可选择本地图片、拖动与双指缩放或滑条裁剪，保存720×1000 JPEG，恢复默认拼图。
- 删除文件夹将书籍归回根书架，不删除源文件、不清除阅读进度或书籍元数据。旧版没有folderId的书保持在根书架。
- 修复书籍和文件夹封面编辑器白底绘制越界遮住弹窗标题的问题；调整书架工具栏右对齐。

## 构建

`testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --offline` 成功。

- 37项单元测试通过，0 failures、0 errors；新增5项文件夹名称与数据保留测试。
- Lint：0 errors、38 warnings。已有和新增警告仍保留，未宣称零警告。

## 设备测试

专用 Android 15 / API 35 模拟器，横屏3390×2400、竖屏2400×3390、density 320。

本次9项不同运行测试通过（分组执行，重复重跑不计为新测试）：

1. `folderLifecycleAndBooksPersist`：UI新建、同名拒绝、选入PDF与EPUB、单本移出、改名、竖屏、界面重建、批量移入、删除取消与确认；比较原书元数据一致。
2. `folderCoverPickerCropsRestoresAndPersists`：真实系统文件选择器，双色测试图的放大和平移裁剪，输出尺寸及红色像素断言，界面重建后封面持久化、恢复默认。
3. `importingInsideFolderAndReturningKeepsFolder`：在夹内通过系统选择器导入PDF、分类、归属、退出和再次打开后返回、系统返回根书架。
4. `folderRepositoryPreservesBookMetadataAndCleansMembership`：旧数据兼容、跨文件夹移动、取消成员、文件夹封面、删除后原书数据不变、拒绝已删除目标文件夹。
5. `launcherUsesAdaptiveMaskWithoutBakedBlackCorners`：实际应用图标类型、圆形／圆角矩形／方形遮罩预览、四角像素无原图黑边。
6. `coverReplacementAndCategoriesPersistWithoutChangingProgress`：原有书籍封面替换、裁剪、恢复、PDF页封面与分类。
7. `removeFromShelfKeepsOriginalAndSurvivesRecreation`：移出书架，保留原文件。
8. `epubOpensPaginatesTurnsAndReturns`：EPUB打开、分页、翻页、恢复、返回。
9. `pdfCanExitWhilePageIsTurning`：PDF翻页过程中返回。

首轮4项、第二轮5项均通过；封面白底及工具栏修正后重跑6项通过。书架图标留边的后续修正只改变显示裁切，另重跑文件夹生命周期和封面流程。

## 视觉证据与边界

- `v190-icon-masks.png`：圆形／圆角矩形／方形图标预览。
- `v190-folder-mosaic-shelf.png`：文件夹拼图书架及标题图标。
- `v190-folder-portrait.png`：文件夹内竖屏布局。
- `v190-folder-cover-editor.png`：文件夹封面裁剪。
- `v190-folder-custom-cover.png`：自定义文件夹封面。

红色封面是双色测试图裁切后的预期结果，不是默认封面。

文件夹为应用内分组，不是磁盘目录；不支持嵌套，一本书只归属一个文件夹。根书架按分类筛选时，仅显示包含匹配书籍的文件夹；无筛选时显示空文件夹。图片保存到应用内部，恢复默认不修改源图片。

模拟器验证不能替代用户实体平板桌面检查；不同桌面遮罩／图标包可能继续影响最终外形。本次没有变更翻页算法，也没有测量实体设备帧率。未运行全部历史书籍兼容测试。

## 交付

- `大肥鱼阅读-1.9.0-debug.apk`，20,815,964 字节。
- SHA-256：`0F3374FCC2BACCE60F4466D983773FF9F09DFF7EF033339BB6759F47583B13C2`。
- APK签名验证通过；证书SHA-256：`29258a00d61f37a2f404652e2ac1462d747a0811dd17a238dabae2e298062b9e`，与旧版相同。
- 覆盖安装，不要先卸载，以免丢失本地书架记录。
