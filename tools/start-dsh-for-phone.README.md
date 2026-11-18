# 让 dsh web 对手机开放

DSH 的 Web 监听拒绝绑定 `0.0.0.0`（那等于把远程代码执行摊到整个网络），
所以要让手机连上，必须显式绑定**本机某个具体网卡地址**，并把这台机器的
authority 加进 `/api` 的浏览器信任栅栏。

用法：

```powershell
# 自动挑一张网卡的 IPv4，打印手机要粘贴的链接前缀，然后启动 web profile
.\tools\start-dsh-for-phone.ps1

# 指定网卡/端口
.\tools\start-dsh-for-phone.ps1 -HostAddress 192.168.1.23 -Port 3080
```

脚本只是把下面这条命令拼出来并执行，等价于手打：

```powershell
dsh --profile web --host 192.168.1.23 --port 3080 --no-open --trusted-host 192.168.1.23:3080
```

启动后终端会打印带一次性 token 的地址，例如：

```
http://192.168.1.23:3080/?token=6Qb…（很长）
```

把这个整串粘进手机 App 的输入框即可。token 每次重启进程都会变，App 里
「设置 → 重新登录（清除 Cookie）」后重新粘贴新链接就行。

## 为什么需要 --trusted-host

`/api` 的浏览器信任栅栏要求请求的 `Host`（以及 `Origin`）要么是环回地址、
要么是本次绑定的网卡地址、要么出现在 `--trusted-host` 里。绑定网卡地址本身
已经放行 Host，但加上 `--trusted-host` 可以避免某些代理/反向代理改写 Host
后 403。

## 走 USB 不想开 Wi-Fi 时

```powershell
# 手机插 USB 并开启 USB 调试
adb reverse tcp:3080 tcp:3080
dsh --profile web --port 3080 --no-open
```

然后 App 里填 `http://127.0.0.1:3080/?token=<终端打印的 token>`。
