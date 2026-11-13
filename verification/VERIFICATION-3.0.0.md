# 大肥鱼阅读 3.0.0 验证记录

验证日期：2026-09-29

## 构建信息

- `versionName`: `3.0.0`
- `versionCode`: `30`
- 构建变体：`debug`
- 测试设备：Android 15 平板模拟器 `TwinLeafVerification`
- 测试分辨率：3390×2400；旋转后 2400×3390

执行命令：

```powershell
./gradlew.bat testDebugUnitTest assembleDebug
```

结果：`BUILD SUCCESSFUL`，132 项 JVM 单元测试通过，Debug APK 成功生成。

另执行 `./gradlew.bat lintDebug`：`BUILD SUCCESSFUL`，0 errors；89 warnings 与 1 hint 主要来自既有绘制分配、旧依赖版本及可访问性提示，不阻断本次构建。

## 自动化覆盖

- `SpreadLayoutTest`：封面单页、传统双页、自适应横置页、尾页、物理页定位和单页卡片分组。
- `EpubLayoutScriptTest`：单／双栏分页、图片保持比例、宽图判定、固定版式保护和图片不反色规则。
- `LibraryCategoryTest`：旧分类解析、稳定 ID、名称去重、“未分类”保护和分类排序。
- 原有批注、无限笔记、触笔校准、云同步、翻页几何、目录、阅读位置等测试继续通过。

## 模拟器验收

1. 使用 2.9.0 已有书架数据覆盖安装 3.0.0，旧 EPUB 仍在书架，旧中文分类筛选正常显示。
2. 打开“管理分类”，确认四个内置分类完成迁移；“未分类”的重命名和删除按钮禁用；新建、排序、单本／批量分类入口存在。
3. EPUB 横屏 AUTO 显示双栏；从设置栏切换 SINGLE 后立即改为单页卡片，水平滑动从第 2 页前进到第 3 页。
4. 旋转竖屏后默认进入连续滚动；点击布局图标切换 CARD，偏好写入 `reading_layout_preferences_v3`，阅读位置保持在同一章节附近。
5. PDF 横屏 AUTO：封面单独显示，下一组将物理第 2、3 页组成双页；页码仍显示物理页 `2 / 8`，不使用视觉组编号。
6. 阅读设置面板分别显示“横屏自动／单页／双页”和“竖屏连续／卡片”的无障碍说明；模式按书、按方向独立保存。
7. 检查 AndroidRuntime 日志，验证期间无应用崩溃。

## 截图

- `v300-shelf.png`：旧书架与分类迁移
- `v300-category-manager.png`：分类管理
- `v300-epub-auto.png`：EPUB 横屏自动双栏
- `v300-epub-single.png`、`v300-epub-card-after-swipe.png`：横屏单页卡片与滑动
- `v300-epub-portrait-scroll.png`、`v300-epub-portrait-card.png`：竖屏连续／卡片
- `v300-pdf-auto.png`、`v300-pdf-auto-double.png`：PDF 自动单／双页

## 已知边界

- 自定义分类和阅读布局偏好当前仅保存在本机，不纳入 OneDrive v2 清单。
- EPUB 不提供手写批注；复杂脚本型 EPUB 会继续按安全静态内容处理。
- 固定版式 EPUB、复杂 SVG 和错误书内 CSS 的最终效果仍受书源质量影响。
- 模拟器验证不能代替 OPPO Pad 4 Pro / ColorOS 16 的触控延迟、内存和高刷帧率实测。
