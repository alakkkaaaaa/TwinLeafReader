# 大肥鱼阅读 2.7.0 验证记录

验证日期：2026-09-26

## 实现范围

- 新增 OneDrive 设备代码登录、令牌刷新与退出登录；Client ID 由用户在应用内配置，APK 不含客户端密钥。
- 使用 Microsoft Graph `Files.ReadWrite.AppFolder` 最小权限，上传每台设备独立的 JSON 快照。
- 支持逐本选择以及阅读进度、PDF 批注、无限笔记三个独立开关；PDF / EPUB 原文件不上传。
- 使用快速内容指纹匹配外部书籍；无限笔记可跨设备创建恢复。
- 进度、批注、笔记分别按更新时间合并，进入书架自动补同步并提供手动立即同步。

## 自动验证

- `:app:compileDebugKotlin`：通过。
- `:app:testDebugUnitTest`：通过，101 项测试、0 失败、0 错误。
- `:app:lintDebug`：通过，0 错误、68 个警告。
- `:app:assembleDebug`：通过，完整构建成功。

## 安装包核验

- 文件：`大肥鱼阅读-2.7.0-debug.apk`（31,844,378 字节）。
- 包名：`com.localreader.twinleaf`。
- 版本：`versionCode 27`、`versionName 2.7.0`。
- 应用标签：`大肥鱼阅读`。
- APK v2 签名校验通过；证书 SHA-256 为 `29258a00d61f37a2f404652e2ac1462d747a0811dd17a238dabae2e298062b9e`。
- 文件 SHA-256：`DC5A33BE5493BEEB019695AFFD228814A2A702E07CAEC535FF6E1EA14A92A4D8`。

## 尚需实机联机验收

- 当前环境没有项目所有者的 Microsoft Entra Client ID，因此未执行真实账户授权和 OneDrive 写入测试。
- 按 `ONEDRIVE_SETUP.md` 注册公共客户端并配置 Client ID 后，验证个人 Microsoft 账户、工作/学校账户、断网重试与令牌刷新。
- 两台设备分别导入同一 PDF，验证进度与批注匹配；在两台设备离线编辑同一批注时确认“较新版本生效”符合首版预期。
- 验证大陆网络环境下前台立即同步的耗时和失败提示；首版未接后台定时任务。
