# 海康 ISUP SDK 放置目录

本目录用于放置**海康官方 ISUP（原 EHome）5.0 SDK** 的二进制文件。
二进制文件不提交到 Git（已在 .gitignore 中排除），请自行从海康开放平台下载后放入。

## 下载地址

1. 打开海康开放平台：<https://open.hikvision.com/>
2. 顶部导航「下载」→「设备集成 SDK」→ 找到 **ISUP SDK（ISUP 5.0 / 原 EHome）**
   - 直达入口：<https://open.hikvision.com/download/5cda567cf47ae80dd41a54b3?type=10>
   - 需要登录海康开放平台账号（免费注册），部分资源需完成企业认证
3. 若平台页面调整找不到 ISUP 入口，可在开放平台「资源下载」中搜索 `ISUP`，
   或联系海康技术支持 / 设备供应商索取 ISUP 5.0 SDK 包

## Linux x86_64（生产部署用这套）

```
sdk/
├── libHCISUPCMS.so        ← CMS 注册库，必需
├── libHCISUPStream.so     ← 流媒体库，必需
├── libcrypto.so           ← 加密库（SDK 包内自带），必需
├── libssl.so              ← SSL 库（SDK 包内自带），必需
└── HCAapSDKCom/           ← 组件库目录，必需（整体拷贝，内含多个 .so）
```

## Windows（本地调试用这套）

```
sdk/
├── HCISUPCMS.dll
├── HCISUPStream.dll
├── libeay32.dll
├── ssleay32.dll
└── HCAapSDKCom\
```

## 注意事项

- CMS 库与 Stream 库必须是**同一个 SDK 包内**的版本，不要混用不同版本
- SDK 版本必须是 **ISUP 5.0 及以上**，否则不支持 `NET_ECMS_StartGetRealStreamV11` 和 ISUP5.0 认证
- 目录路径**不要含中文或空格**，否则 JNA 可能加载失败
- Linux 下若提示 `libcrypto.so` 版本冲突，优先使用 SDK 包自带的版本（本项目已指定该路径加载）
- 放好后重启服务，日志出现 `海康 ISUP SDK 加载完成` 即表示加载成功
