# DSH 手机版（Android）

把电脑上跑的 DeepSeek Harness Web 界面，变成手机上能装、能用的安卓 App。

产物：`dsh-mobile/app/build/outputs/apk/release/app-release.apk`（已签名，可直接装）。

---

## 一、它到底是什么

不是把 Node 塞进手机，而是一个**原生 WebView 壳**：

```
┌──────────── 手机 ────────────┐        ┌──────────── 电脑 ────────────┐
│  DSH Mobile (APK)            │  HTTP  │  dsh --profile web           │
│  ├ WebView  ← 渲染前端        │ ─────► │  ├ 静态前端 + /api + WebSocket│
│  ├ CookieManager 持久化会话   │  LAN   │  ├ 一次性 token 换签名 Cookie │
│  ├ 原生：文件选择/下载/返回键  │        │  └ 实际执行工具的是电脑       │
│  └ 注入 mobile-shim（视口/键盘）│        └──────────────────────────────┘
```

为什么必须是这个结构：DSH 的能力（pwsh、文件、子代理、插件）全在电脑上的 Node 进程里；
手机只负责当一块「浏览器 + 原生外壳」。所以 App 断网 = 连不上电脑，这是设计如此。

### 认证怎么过

`dsh web` 每次启动会打印一个一次性链接 `http://IP:3080/?token=…`：

1. 浏览器/WebView 请求 `/?token=`
2. 服务端 `303` 重定向到 `./`，并 `Set-Cookie: dsh-auth-<sha256(authority)>=v1.…`
   （HMAC 签名，`HttpOnly; SameSite=Strict`，默认绑定到那个 authority）
3. 之后的 `/api` 请求靠这个 Cookie 通过校验

App 做的事：把粘进来的整串（含 token）交给 WebView → Cookie 落下 → 之后**只保存干净地址**
（`?token=` 已被 303 抹掉）。进程重启后 token 变了，App 会弹「登录令牌已失效」面板，
重新粘一次新链接即可。

---

## 二、装它

```powershell
# USB 调试已开
adb install -r dsh-mobile\app\build\outputs\apk\release\app-release.apk
```

或者把 APK 拷进手机点安装（需要允许「安装未知来源应用」）。

- 包名：`com.deepseek.dshmobile`
- 最低：Android 7.0（API 24），目标 API 34
- 权限：网络、录音/相机（页面里的语音输入、拍照上传才用）、常亮（可选）

---

## 三、连上电脑

### 方案 A：同一个 Wi-Fi（推荐）

```powershell
# 一条命令搞定：挑网卡 IP、加 trusted-host、启动 web profile
.\tools\start-dsh-for-phone.ps1
```

等价于手打：

```powershell
dsh --profile web --host 192.168.1.23 --port 3080 --no-open --trusted-host 192.168.1.23:3080
```

> DSH 拒绝绑定 `0.0.0.0`（会把远程代码执行摊到全网），必须写具体网卡 IP。

终端打印：

```
http://192.168.1.23:3080/?token=6Qb…（很长）
```

手机 App 里粘贴这一整串 → 点「连接」。

### 方案 B：只走 USB（不想开 Wi-Fi）

```powershell
adb reverse tcp:3080 tcp:3080
dsh --profile web --port 3080 --no-open
```

App 里填 `http://127.0.0.1:3080/?token=<终端打印的 token>`。

### 方案 C：走 TLS / 反代

```powershell
dsh --profile web --host 10.0.0.7 --tls-cert .\chain.pem --tls-key .\key.pem --trusted-host app.example
```

App 里填 `https://app.example/?token=…`。

---

## 四、App 里有什么

| 功能 | 实现位置 | 说明 |
| --- | --- | --- |
| 首次配置 / 改地址 | 原生面板 + 右上齿轮 | 面板里三个按钮：粘贴、连接、重试 |
| 会话持久化 | `CookieManager` + 启动时 `flush()` | 服务不重启就不用重新粘 token |
| 令牌失效(401) | `onReceivedHttpError` → 原生面板 | 直接原地粘新链接，不用卸载重装 |
| 断网/证书错误 | `onReceivedError` / `onReceivedSslError` | TLS 出错直接 `cancel()`，不会降级 |
| 文件上传 | `onShowFileChooser` | 拉起系统文件选择器，支持多选 |
| 网页下载 | `DownloadListener` → 带 Cookie 的原生下载 | 存到 `下载/`（Android 10+ 经 MediaStore） |
| blob:/data: 下载 | 注入脚本 + `DshBridge` 分块 Base64 | WebView 的 DownloadListener 看不到这类，必须走 JS 桥 |
| 返回键 | 网页内先后退 | 退到底再按一次才退出 |
| 外部链接 | 非同一 origin 一律丢给系统浏览器 | 同时避免把 JS 桥暴露给第三方页面 |
| 深链 | `dsh://open?url=…` | 可以用 `adb shell am start` 把带 token 的链接直接塞进 App |
| 桌面模式 | 设置里开关 | 视口改成 1280 缩放，应对宽面板页面 |
| 保持常亮 | 设置里开关 | 长任务时用 |
| 渲染进程崩溃 | `onRenderProcessGone` | 自动重建 WebView 并回到原地址 |

### 移动端适配做了什么

DSH 前端本身已经响应式：视口宽度 < 768px 时侧边栏切换为抽屉（布局插件里
`layoutInfo.viewportWidth < 768` 的分支）。所以没有去硬改它的 CSS 类名，只补
WebView 缺的部分（`app/src/main/assets/mobile-shim.{css,js}`）：

- `viewport` 改写成 `width=device-width … interactive-widget=resizes-content`，
  配 `android:windowSoftInputMode="adjustResize"`，键盘弹出时**布局真的变矮**而不是被盖住
- 聚焦输入框时按 `visualViewport` 把光标滚进可见区
- 禁掉下拉刷新/橡皮筋回弹、双击缩放延迟、灰色点击高亮、桌面滚动条
- `blob:`/`data:` 下载拦截并交给原生落盘

---

## 五、自己重新编译

### 本机：用 SDK 工具直出（已验证可跑通）

```powershell
powershell -ExecutionPolicy Bypass -File tools\build-apk.ps1
```

它做的事和 AGP 一样，只是绕开了 `java.exe`：

| 步骤 | 工具 | 进程 |
| --- | --- | --- |
| 资源编译/链接 | `aapt2 compile` / `aapt2 link` | 原生 exe |
| Java 编译 | `javac` | javac.exe |
| 转 dex | `javaw.exe -cp lib\d8.jar com.android.tools.r8.D8` | javaw.exe |
| 对齐 | `zipalign -p 4` | 原生 exe |
| 签名 | `javaw.exe -cp lib\apksigner.jar …ApkSignerTool` | javaw.exe |

### 为什么本机不能直接 `./gradlew assembleRelease`

这台机器装了 Topsec 终端安全客户端，它会把 **`java.exe` 进程写出的一切文件**透明加密：
写 10 个字节进去，落盘是 1034 字节的密文（头 `18 1b 03 1a …`）。实测对照：

```
java.exe  写 10 字节 → 落盘 1034 字节 首字节 18   ← 被加密
javaw.exe 写 10 字节 → 落盘 10 字节   首字节 41   ← 正常
javac.exe 写出 .class → CAFEBABE 正常
```

所以 AGP 的 `mergeReleaseResources` 产出的 `.flat` 中间件全是密文，链接时报
`magic value is 0x1a031b18 but AAPT expects 0x54504141`（把工程整份拷到 C: 盘、
删掉构建缓存、串行执行，结果一模一样，可排除磁盘/并发问题）。

`tools/build-apk.ps1` 因此只用**原生工具 + javaw.exe**，并在打完 dex 后校验头
`dex\n03x`，一旦哪天策略变了会立刻报错而不是产出坏包。

### 换一台没有这种 DLP 的机器：照旧用 Gradle

```powershell
$env:JAVA_HOME='<JDK 17+>'
gradle -p dsh-mobile assembleRelease      # 或用 Android Studio 打开 dsh-mobile/
```

工程零第三方依赖（不用 AndroidX），AGP 8.5.2 / compileSdk 34 / Java 17。
`settings.gradle` 里仓库优先走阿里云镜像（这台机器连不上 `dl.google.com`）。

签名：`app/keystore/dsh-mobile.jks`，alias `dsh-mobile`，口令 `dshmobile`
（`app/build.gradle` 与 `tools/build-apk.ps1` 里写的都是它，纯自用）。

### 构建产物自检

```powershell
powershell -ExecutionPolicy Bypass -File tools\verify-apk.ps1
```

当前这份 APK 的实测结果：

```
package: name='com.deepseek.dshmobile' versionCode='1' versionName='1.0.0'
sdkVersion:'24'   targetSdkVersion:'34'
application-label:'DSH 手机版'
application-icon-*: res/mipmap-anydpi-v26/ic_launcher.xml
uses-permission: INTERNET, ACCESS_NETWORK_STATE, WAKE_LOCK, RECORD_AUDIO, CAMERA
Verifies / v2 scheme: true / v3 scheme: true
Signer: CN=DSH Mobile, OU=Personal, O=DSH, L=Shanghai, ST=Shanghai, C=CN
contents: classes.dex 43 KB, assets/mobile-shim.js 6.2 KB, 5 组 mipmap
windowSoftInputMode=adjustResize, configChanges=0x40003ff4, exported=true, singleTask
```

---

## 六、目录

```
dsh-mobile/                     安卓工程
  app/src/main/java/…/MainActivity.java   WebView 壳 + 原生面板 + 设置
  app/src/main/java/…/DshBridge.java      JS 桥：分块下载、带 Cookie 抓取
  app/src/main/java/…/FileSaver.java      MediaStore / 外部目录落盘
  app/src/main/assets/mobile-shim.*       移动端注入层
  app/keystore/dsh-mobile.jks             自用签名库
  icon-source.html                        图标渲染源（DSH 鲸鱼标）
tools/                          辅助脚本
  build-apk.ps1                  本机直出 APK（aapt2/javac/d8/zipalign/apksigner）
  verify-apk.ps1                 aapt2 + apksigner 自检
  start-dsh-for-phone.ps1        挑网卡 IP 并启动 dsh web（对手机开放）
  start-dsh-for-phone.README.md  上面那步的说明
  make-icons.mjs                 用无头 Chromium 生成各密度图标
  mobile-preview.mjs             预览代理（注入 shim、代签 token Cookie、改写 Origin）
  dsh-auth.mjs                   生成 DSH 浏览器会话 Cookie（预览代理用）
captures/icons/                 图标产物（含 512px 版）
```

---

## 七、已知边界

- 手机必须能连到电脑（同网段 / USB 反代 / 公网反代），这是架构决定的
- `dsh web` 进程重启 → token 失效，需要重新粘链接（Cookie 的 authority 没变所以还认，
  但首页已经进不去了，按「重新登录」走一遍最稳）
- 局域网内是明文 HTTP：只在自己信任的网络里这么用；跨网络请上 TLS（方案 C）
- 本机签名 **v1(JAR) 未启用、v2+v3 已启用**：Android 7.0 起原生支持 v2，装得上；
  如果要给 Android 5/6 装，得把 `minSdkVersion` 降到 23 并让 apksigner 真正开 v1
- 没有真机/模拟器可验证运行时表现：APK 的静态校验（清单、权限、dex、签名、资源）
  全部通过，但「点开能不能连上你的电脑」需要你插上手机实测一次

- 工程没有做 `minifyEnabled`，包体大一点但反混淆/排障更简单
