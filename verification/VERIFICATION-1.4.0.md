# 大肥鱼阅读 1.4.0 验证

2026-09-15；包名 `com.localreader.twinleaf`；versionCode 5。

安装包 SHA-256：`A9E514254928622461A649DB32C4FFC93B2EEB3B5377341EBEF82308597D1991`。签名验证通过，证书与 1.3.0 一致。

## 已验证

- 构建、Lint、15 项单元测试通过。其中新增 6 项灵敏度测试，覆盖短拖动、快速轻扫、抖动、反向取消和进度边界。
- 3390×2400、density 320、Android 15 模拟器：4 项运行测试通过，覆盖 EPUB 打开和进度保存恢复、PDF 翻页中返回、150 px 慢拖动提交、竖向滑动不翻页、书架显示、纯图标设置和日夜切换。
- 书架截图使用 10 本本地 EPUB 及 1 本合成 PDF 测试书；这些书籍不会打包进应用。
- 设置面板无可见中文文字，图标和滑条保留中文无障碍名称。
- 未在实体平板测试性能，不宣称达到特定帧率。
- 最后调整启动底色后重新通过构建、Lint 和设备界面测试，额外确认 PDF 筛选隐藏 EPUB 卡片、夜间模式实际正文底色变暗。

## 视觉交付

- `v140-bookshelf.png`：新书架。
- `v140-settings.png`：默认设置面板。
- `v140-settings-brightness.png`、`v140-settings-night.png`：亮度、夜间状态。

## 图标边界

使用上一轮内置图像生成工具重绘的图标，原稿保存于 `artwork/dafeiyu-icon.png`。其圆角外侧是黑色 RGB 像素，不是真实透明背景。尝试通过 imagegen 技能做背景透明化时，内置工具返回 usage_limit_reached，未产生新图片，也没有调用收费 API 备用方案。因此本版保留已审核图案，透明外角尚未完成。

透明化尝试的提示词：

Use case: background-extraction. Edit target: input image 1, an approved rounded-rectangle app icon. ONLY remove the black area outside the rounded rectangular tile and replace it with genuine TRANSPARENCY (RGBA PNG alpha=0), NOT black, NOT white, NOT checkerboard pixels. Preserve everything inside the rounded rectangle: exact blue-haired chibi girl, face, open whale book, blue colors, outlines, layout, pale blue tile background. Keep original square aspect ratio and rounded rectangle contour. Clean anti-aliased transparent edges; no black halo. This is a production app icon asset, no artistic redesign, no added elements. The output file MUST have a real alpha channel, with transparent outer corners.
