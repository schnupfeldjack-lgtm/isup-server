# Linux x86_64 SDK 放置目录

本目录的文件会在启动时被释放到 `isup.sdk-dir`（默认 `./sdk`）后加载，
随 jar 一起分发，服务器上就不必再单独拷 SDK。

需要放入的文件（从海康开放平台下载 **ISUP SDK_Linux64**，
不要用「设备网络 SDK / HCNetSDK」）：

```
libHCISUPCMS.so
libHCISUPStream.so
libcrypto.so
libssl.so
HCAapSDKCom/          ← 整目录拷入
```

放好后核对：`ls -R src/main/resources/native/linux64`。

注意：`.gitignore` 默认忽略所有 `*.so`，本目录已加白名单例外，
确认提交是否生效用：

```bash
git check-ignore -v src/main/resources/native/linux64/libHCISUPCMS.so   # 无输出 = 会进 git
git status --porcelain src/main/resources/native
```

若 jar 里带了本目录的文件，运行时**优先用外部 `./sdk` 目录**，
外部目录缺哪个文件才从 jar 里补哪个，方便不动包换 SDK。
