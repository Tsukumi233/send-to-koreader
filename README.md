# Send to KOReader

Kindle 的 "Send to Kindle" 体验，换成 KOReader + 你自己的 WebDAV 云盘。

在手机上任意 App 里分享电子书（或微信/QQ 的"用其他应用打开"），选 **发送到 KOReader**，书就直接上传到你配置好的 WebDAV 文件夹；KOReader 那边一两分钟内自动下载，并弹出"已收到新书"提示。

```
手机 ──分享──▶ Send to KOReader ──PUT──▶ WebDAV 文件夹 ◀──扫描/下载── KOReader (RemoteLibrary + 自动刷新补丁)
```

## 组成

| 部分 | 说明 |
|---|---|
| Android App（本仓库根目录） | 分享目标，上传到 WebDAV。支持多文件、进度、失败重试。无第三方依赖，APK 约 25 KB |
| [`koreader/2-remotelibrary-autorefresh.lua`](koreader/2-remotelibrary-autorefresh.lua) | KOReader 用户补丁：定时 / 联网 / 唤醒时扫描云端文件夹，自动下载新书 |
| [RemoteLibrary.koplugin](https://github.com/dani84bs/RemoteLibrary.koplugin) | 第三方 KOReader 插件，把云端文件夹映射成书库，需要单独安装 |

## 手机端

1. 从 [Releases](../../releases) 下载 APK 安装（Android 10+）。
2. 打开 App，填写 WebDAV **文件夹**地址（例如 `https://example.com/dav/books`）、用户名、密码，点"保存并测试"。文件夹不存在时会提示创建。
3. 之后在任意 App 里分享电子书即可。

支持 epub、pdf、mobi/azw、fb2、cbz/cbr、djvu、txt、docx 等。凭据只保存在 App 私有存储中（已关闭备份）。

> 测试连接用的是"上传并删除一个探测文件"，因为 Android 的 `HttpURLConnection` 不支持 `PROPFIND`。如果你的账号没有删除权限，云端会留下一个 `.send-to-koreader-test` 文件，可以忽略。

## KOReader 端

1. 安装 [RemoteLibrary.koplugin](https://github.com/dani84bs/RemoteLibrary.koplugin)，在"云存储"里添加同一个 WebDAV 服务器，并把 Remote Library 的云端文件夹设为同一个文件夹。
2. 把 `koreader/2-remotelibrary-autorefresh.lua` 复制到 KOReader 的 `patches/` 目录（例如 Kindle 上的 `/mnt/us/koreader/patches/`），重启 KOReader。

补丁行为：

- 每 2 分钟（设备醒着且在线时）、Wi-Fi 连上、从休眠唤醒、启动后各扫描一次；最短间隔 60 秒。
- 扫描和下载都在子进程里进行，不阻塞界面。
- 只自动下载**上次扫描之后新出现**的书（单本上限 300 MB），云端原有的书保持"仅云端"，不会一股脑全下下来。
- 需要服务器支持 `PROPFIND Depth: infinity`（OpenList / AList、Nginx、Apache 等一般都支持）。

### 已知问题

RemoteLibrary 0.2.0 有两个 bug，合并前需要手动修：

- WebDAV 地址带路径（如 `/dav`）时手动下载 404 —— [dani84bs/RemoteLibrary.koplugin#7](https://github.com/dani84bs/RemoteLibrary.koplugin/pull/7)
- 插件的 `provider.lua` 与 KOReader 核心模块重名，导致导出插件失效 —— [dani84bs/RemoteLibrary.koplugin#5](https://github.com/dani84bs/RemoteLibrary.koplugin/pull/5)

## 构建

不用 Gradle，直接调用 SDK 工具（aapt2 → javac → d8 → zipalign → apksigner）：

```sh
./build.sh
```

需要 Android SDK build-tools 36.0.0、platform android-37、JDK 17+。`ANDROID_HOME` 未设置时默认 `%LOCALAPPDATA%/Android/Sdk`。脚本目前按 Windows（Git Bash）写的，其他平台把 `.exe` / `.bat` 后缀去掉即可。签名使用 `~/.android/send-to-koreader-release.jks`（密码放在同目录的 `.pass` 文件里，可用 `RELEASE_KEYSTORE` 指定其他路径），找不到时退回 debug keystore。

`test/` 下有一个 Python 模拟 WebDAV 服务器和 JVM 测试桩，用来在电脑上测试 `WebDav.java`。

## License

MIT
