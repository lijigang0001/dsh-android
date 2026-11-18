# 让 DSH 在安卓手机上本地运行（端口记录）

目标：手机装一个 APK，DSH 的 Node 后端**跑在手机上**，不依赖电脑。

## 一、先确认的硬事实（都有实测）

### 1. DSH 在 Android 上会直接崩，原因是原生 addon

`dsh-app-boot` 启动时无条件加载 `node-addon-require-builtin`：

```
internalModules()  →  createRequire(...)("node-addon-require-builtin")
installRuntimeInterception()  →  new PluginPackages(...)
```

该包的 `optionalDependencies` 只覆盖 darwin / linux-gnu / linux-musl / win32，
**没有 android/bionic**，且 README 明确写着 "Published installs do not ship native
sources and fail closed"。实测（用 `tools/block-native-addon.cjs` 模拟）：

```
dsh: fatal uncaught exception: Error: dsh: host preparation failed:
  simulated android host: node-addon-require-builtin has no prebuild for linux-android arm64
  at internalModules (dsh-app-boot/lib/index.js:2566)
  at installRuntimeInterception (…:2633)
  at new PluginPackages (…:4251)
```

→ **Termux 也一样会崩**（Termux 就是 android）。这一步不解决，任何"手机上跑"的方案都不成立。

### 2. 解决办法：纯 JS 替身 + `--expose-internals`

那个 addon 的全部作用就是把 id 转发给 Node 的 builtin require。
`cordis-plugin-loader` 自己就留了无 addon 的路径：

```js
if (process.execArgv.includes("--expose-internals")) try { return require(id); } catch {}
try { return require("node-addon-require-builtin").requireBuiltin(id); } catch {}
```

所以用 `tools/android-native-addon-shim.cjs`（`requireBuiltin = require`）+
`tools/android-addon-preload.cjs` 劫持模块解析，配上 `--expose-internals`：

```
node --expose-internals --require tools/android-addon-preload.cjs <dsh>/lib/bin.js \
     --profile web --host 127.0.0.1 --port 3096 --no-open
→ dsh web: http://127.0.0.1:3096/?token=FOIa5v2cwec48vCBMpyg9WrcATK7oVBj8E_f0Efvdmo
```

**实测能起来并正常 serve**，与原生 addon 路径等价。这是整个端口的突破口。

### 3. 运行时：Termux 的 Node 就是 Android 原生二进制

从清华镜像取 Termux aarch64 包（`packages.termux.dev` / `mirrors.tuna.tsinghua.edu.cn/termux`）：

| 包 | 版本 | 用途 |
| --- | --- | --- |
| nodejs | 26.13.1 | 主程序（满足 dsh `engines: >=24.9.0`） |
| libc++ / openssl / c-ares / libicu / libsqlite / zlib / libffi | | DT_NEEDED 依赖 |

`bin/node` 实测：`ELF64 · AArch64 · DYN`，解释器 `/system/bin/linker64`，
RUNPATH 原为 `/data/data/com.termux/files/usr/lib`（绝对路径，出不了 Termux 沙箱）。

### 4. 让二进制脱离 Termux 前缀

`tools/patch-runpath.py` 把 RUNPATH 就地改写成 `$ORIGIN/../lib`（新串更短，
直接覆盖原字符串，d_val 偏移不动）。已对 17 个 ELF 生效，readelf 复核：

```
node          RUNPATH  [$ORIGIN/../lib]
libssl.so.3   RUNPATH  [$ORIGIN/../lib]
libicuuc…     RUNPATH  [$ORIGIN/../lib]
```

### 5. 符号链接必须由 App 建，不能塞进 APK

`tar` 提取时 symlink 被跳过，而 DT_NEEDED 用的是 soname：

```
libicudata.so.79 → libicudata.so.79.1   (32 MB，绝不能重复打包)
libicui18n.so.79 → libicui18n.so.79.1
libicuuc.so.79   → libicuuc.so.79.1
libsqlite3.so    → libsqlite3.so.3.54.1
libz.so.1        → libz.so.1.4
```

App 首次启动时在私有目录建 symlink（`Os.symlink`，应用沙箱内允许）。

当前 payload：`dsh-android/payload/runtime` = 12 个文件、95.8 MB（node 48.6 + icudata 32 + 其余）。

## 二、还需要做的（按顺序）

1. **dsh 依赖树裁剪**：装的是 win32-x64 布局（`dsh-win32-process`、`@img/sharp-win32-x64`、
   `@koromix/koffi-win32-x64`、`node-pty/win32-*`…）。Android 上要：
   - 删掉所有 `*-win32-*` / `*-darwin-*` 可选包；
   - 避开无 Android 预编译的 native：`sharp`（图片附件）、`node-pty`（终端面板）、
     `node-pty`/`ripgrep` 的 win32 二进制、`sherpa-onnx`（语音）；
   - 用 `--expose-internals` + shim 顶掉 `node-addon-require-builtin`。
2. **profile 换壳**：`dsh-bash-local` / `dsh-tool-bash` 已在树里（Android 上 bash 存在），
   `dsh-pwsh-*` 在 Android 上无效 → 用 cordis patch overlay 把 pwsh 工具换掉。
3. **APK 壳**（新工程 `dsh-android`）：
   - `targetSdk 28`（必须：API 29+ 禁止从应用私有目录 `exec()` 二进制）；
   - 首次启动：解包 `assets/runtime` + `assets/dsh` 到 `filesDir`，建 symlink，`chmod 755`；
   - `Runtime.exec(["…/runtime/bin/node", "--expose-internals", "--require", "…/preload.cjs",
     "…/dsh/lib/bin.js", "--profile", "web", "--host", "127.0.0.1", "--port", "0", "--no-open"])`；
   - 抓 stdout 里的 `?token=…`，直接交给 WebView 加载（用户零输入）；
   - 进程守护：Activity 前台时保活，退出时 kill；日志落在 `filesDir/logs`，界面可看。
4. **上机验证清单**（无模拟器/真机时唯一能做的）：`aapt2 dump badging`、`apksigner verify`、
   APK 内 assets 完整性、dex 里关键类；运行时行为必须真机点一遍。

## 三、已验证 / 未验证

| 项 | 状态 |
| --- | --- |
| 缺原生 addon 时 DSH 会崩 | ✅ 实测复现 |
| JS 替身 + `--expose-internals` 能顶住 | ✅ 实测启动并 serve |
| Node 26.13.1 aarch64 满足 dsh engines | ✅ |
| node 二进制是 Android 可执行（linker64） | ✅ readelf |
| RUNPATH 可脱离 Termux 前缀 | ✅ readelf 复核 |
| 依赖树在 Android 上能加载 | ⬜ 待做（步骤 1） |
| APK 首启解包 + exec + 自动登录 | ⬜ 待做（步骤 3） |
| 真机跑通 | ⬜ 需要设备 |
