# 大肥鱼阅读 2.0.1：书架拖动整理

最终验证日期：2026-09-18。版本代码 12，包名 `com.localreader.twinleaf`。

## 交付

- 文件：项目根目录 `大肥鱼阅读-2.0.1-debug.apk`，30,155,965 字节。
- SHA-256：`F3BA32B65153012E40268C5D4CF619D66E236E0B1C4288495F7A52FB1327E02C`。
- 签名证书 SHA-256：`29258a00d61f37a2f404652e2ac1462d747a0811dd17a238dabae2e298062b9e`，与 2.0 一致，已成功覆盖安装到测试模拟器。
- 旧版交付文件未覆盖。安装新版本时不要先卸载，以保留本地书架和批注。

## 操作约定

- 长按图书后拖动，在其他书位松手调整顺序；让位预览仅在内存中，松手后写入排序。
- 根书架拖到文件夹中央，高亮后松手移入。文件夹内拖动时顶部显示移回根书架的区域。
- 移走书籍后按网格连续排布，无永久空位；拖动中淡化的卡片是临时落位预览。
- 靠近上下边缘时自动滚动。拖动期间关闭普通手指滚动，避免纵向拖动被列表滚动抢占。
- 取消拖动或在网格外松手，恢复原顺序。点击仍打开书籍，省略号仍提供管理菜单。
- 根书架首次拖动后记住手动顺序，阅读时间变化不重新洗牌。文件夹内拖动切换到手动排序。
- 筛选后仅替换可见书籍在完整顺序中的位置，隐藏书籍保留自己的位置。移入、移出只改变软件内归属，不移动源文件。
- 文件夹仍固定展示在根书架图书前，不支持文件夹拖动、嵌套或批量拖动。批量选择模式禁用拖动。
- 此次未实施上一轮讨论的按钮入口合并。

## 构建与测试

最终执行 `assembleDebug`、`assembleDebugAndroidTest`、`testDebugUnitTest`、`lintDebug` 成功。

- 单元测试：49 项，0 失败；其中新增 5 项覆盖插入补位、筛选槽位、重复及失效目标、空拖动、根目录手动顺序。
- Lint：0 错误，50 警告。新增提示为 SharedPreferences 编辑的 KTX 写法建议；非无警告构建。
- Android 15 / API 35 专用模拟器上的最终界面测试：7 项全部通过。

界面测试清单：

1. `ShelfDragTest.rootDragReordersThenDropsIntoFolderAndCompacts`：根书架长按排序、移入文件夹、根序列补齐、不误打开。
2. `ShelfDragTest.canceledDragDoesNotPersistAndTapStillOpens`：取消不写入，随后点击正常。
3. `ShelfDragTest.folderDragUsesManualOrderAndCanReturnBookToRoot`：文件夹内排序切为手动，拖到顶部移回书架。
4. `ShelfDragTest.filteredDragPreservesHiddenBookAndHasNoEmptySlot`：分类筛选后的排序保留隐藏书籍。
5. `ShelfDragTest.holdingAtBottomAutoScrollsWithoutLosingTheDraggedBook`：长列表边缘拖动与自动滚动，书籍数量及唯一性不变。
6. `ReaderV2Test.folderSortPersistsAcrossModesAndAppRecreation`：原有排序面板、模式切换与重建恢复。
7. `ReaderRegressionTest.folderCoverPickerCropsRestoresAndPersists`：原有文件夹封面选取、裁剪、恢复及持久化。

界面截图：`v201-shelf-drop.png`，记录图书移入文件夹后的根书架连续排布。

本次没有连接 OPPO 实体设备；实际触摸手感、振动及高刷新率表现仍需实机验收。2.0 已有批注／导出／深色功能未在此次全部重跑，之前的验证记录见 `VERIFICATION-2.0.md`。
