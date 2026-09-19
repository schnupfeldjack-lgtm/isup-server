# ISUP Server

海康 ISUP 5.0 接入服务。面向 ERP 提供 **设备注册、实时预览、按时间段回放、按时间段录像下载** 四个能力，ERP 通过 HTTP API 调用，不直接操作海康 SDK。

技术栈：Java 21 + Spring Boot 3 + Maven 单体应用 + JNA 调用海康 HCISUP SDK + FFmpeg 转封装。

**不使用** MySQL / Redis / Nacos / 微服务 / 前端管理页面。设备与会话状态全部保存在内存。

---

## 1. 项目用途

```
海康 NVR / IPC
      ↓ ISUP 5.0（设备主动注册 + 主动推流）
  isup-server            ← 本服务
      ↓ HTTP API（X-API-Key 鉴权）
      ERP
```

- ERP 不接触海康 SDK，只调用本服务的 REST 接口
- 预览 / 回放输出 HLS，浏览器可直接播放
- 录像下载输出 MP4，异步执行，不阻塞 HTTP 请求

---

## 2. 架构图

```
┌──────────────┐        ISUP 5.0 注册            ┌─────────────────────────────┐
│  海康 NVR    │ ──────────────────────────────▶ │  CMS 注册服务  TCP 7660     │
│  / IPC       │       (AUTH/SessionKey/DAS)     │  NET_ECMS_StartListen       │
│              │                                 └─────────────┬───────────────┘
│              │        实时预览码流(PS)                        │
│              │ ──────────────────────────────▶ ┌─────────────▼───────────────┐
│              │        回放/下载码流(PS)         │  流媒体监听                 │
│              │ ──────────────────────────────▶ │  预览       TCP 8003        │
└──────────────┘                                 │  回放/下载  TCP 8004        │
                                                 └─────────────┬───────────────┘
                                                               │ FFmpeg（只转封装 -c copy）
                                                 ┌─────────────▼───────────────┐
                                                 │  HLS 切片 / MP4 文件         │
                                                 │  ./data/media/...           │
                                                 └─────────────┬───────────────┘
                                                               │ HTTP /media/**
┌──────────────┐      HTTP API（X-API-Key）      ┌─────────────▼───────────────┐
│     ERP      │ ─────────────────────────────▶ │  Spring Boot 3   TCP 8080   │
└──────────────┘                                └─────────────────────────────┘
```

### 端口一览（重点）

| 端口 | 用途 | 方向 | 说明 |
|------|------|------|------|
| **7660** | CMS 注册服务 | 设备 → 服务端 | `NET_ECMS_StartListen`，设备注册 / 认证 / SessionKey / DAS |
| **8003** | Preview 预览监听 | 设备 → 服务端 | `NET_ESTREAM_StartListenPreview`，实时预览推流 |
| **8004** | Playback / Download 监听 | 设备 → 服务端 | `NET_ESTREAM_StartListenPlayBack`，回放与下载共用 |
| **8080** | HTTP API | ERP → 服务端 | 业务接口 + HLS 播放地址 `/media/**` |

> 监听地址（`listen-ip`）与回传给设备的地址（`public-ip`）**必须分开配置**。
> 绝不能把 `0.0.0.0` 或 `127.0.0.1` 返回给公网设备，服务启动时会直接校验并拒绝启动。

### 目录结构

```
src/main/java/com/hiki/isup/
├── config/         IsupProperties、WebConfig、ApiKeyInterceptor
├── controller/     IsupController、ApiExceptionHandler
├── sdk/            HCISUPCMS、HCISUPStream、HikSdkStructure、NativeSdkLoader
├── callback/       DeviceRegisterCallback、Preview/Playback 的 newlink 与 data 回调
├── service/        IsupService、DeviceRegistry、SessionManager、
│                   PreviewService、PlaybackService、DownloadService、FfmpegService
├── model/          DeviceInfo、MediaSession、SessionType、SessionStatus、request/response
├── util/           MediaPaths、TimeRange
└── exception/      IsupException、BadRequestException、NotFoundException
```

---

## 3. JDK 21 安装要求

```bash
# Ubuntu / Debian
sudo apt update && sudo apt install -y openjdk-21-jdk
java -version      # 需要 21.x

# CentOS / Alibaba Cloud Linux
sudo yum install -y java-21-openjdk-devel
```

- JDK 21（编译与运行都是 21，`pom.xml` 中 `java.version=21`）
- Maven 3.6+（构建用，运行只需要 jar 包）
- 内存建议 ≥ 1GB（每条预览/回放会启一个 ffmpeg 进程）

---

## 4. FFmpeg 要求

```bash
# Ubuntu / Debian
sudo apt install -y ffmpeg

# CentOS / Alibaba Cloud Linux
sudo yum install -y epel-release
sudo yum install -y ffmpeg ffmpeg-devel

ffmpeg -version
```

- 服务只做**封装转换，不转码**（默认 `-c copy`），CPU 占用很低
- ISUP 码流为 PS 封装，ffmpeg 以 `-f mpeg` 从 stdin 读取
- 若 `ffmpeg` 不在 PATH 中，用 `ISUP_FFMPEG_PATH=/usr/local/bin/ffmpeg` 指定

---

## 5. 海康 SDK 放置方式

> 海康官方 SDK 二进制文件**不会**提交到 Git（已被 `.gitignore` 忽略），需要自行从海康开放平台下载后放入 `sdk/` 目录。

默认 SDK 目录：`./sdk`（可用 `ISUP_SDK_DIR` 覆盖，建议使用绝对路径）。

### Linux x86_64（生产部署以此为准）

```
sdk/
├── libHCISUPCMS.so
├── libHCISUPStream.so
├── libcrypto.so
├── libssl.so
└── HCAapSDKCom/          # 组件库目录（里面还有若干 .so）
```

### Windows（本地调试用）

```
sdk/
├── HCISUPCMS.dll
├── HCISUPStream.dll
├── libeay32.dll
├── ssleay32.dll
└── HCAapSDKCom\
```

### 需要的 SDK 版本

- ISUP SDK（EHome）**5.0 及以上**，支持 `NET_ECMS_StartGetRealStreamV11` 与 ISUP5.0 认证
- CMS 库与 Stream 库版本必须配套（同一个 SDK 包内）
- `HCAapSDKCom` 目录必须一起放到 `sdk/` 下，否则 SDK 初始化后无法正常注册

加载顺序（已严格对照海康 Demo 实现）：

```
SetSDKInitCfg(0, libcrypto 路径) → SetSDKInitCfg(1, libssl 路径) → Init() → SetSDKLocalCfg(5, HCAapSDKCom 目录)
```

---

## 6. application.yml 配置

完整配置（`src/main/resources/application.yml`）：

```yaml
server:
  port: ${SERVER_PORT:8080}

isup:
  enabled: ${ISUP_ENABLED:true}
  sdk-dir: ${ISUP_SDK_DIR:./sdk}
  public-ip: ${ISUP_PUBLIC_IP:}
  ehome-key: ${ISUP_EHOME_KEY:}
  api-key: ${ERP_API_KEY:}
  ffmpeg-path: ${FFMPEG_PATH:ffmpeg}
  media-dir: ${ISUP_MEDIA_DIR:./data/media}
  link-mode: ${ISUP_LINK_MODE:0}

  cms:
    listen-ip: ${ISUP_CMS_LISTEN_IP:0.0.0.0}
    listen-port: ${ISUP_CMS_LISTEN_PORT:7660}

  stream:
    listen-ip: ${ISUP_STREAM_LISTEN_IP:0.0.0.0}
    preview-port: ${ISUP_PREVIEW_PORT:8003}
    playback-port: ${ISUP_PLAYBACK_PORT:8004}

  ffmpeg:
    ps-input-format: ${ISUP_FFMPEG_PS_INPUT_FORMAT:mpeg}
    codec-args: ${ISUP_FFMPEG_CODEC_ARGS:-c copy}
    hls-time: ${ISUP_FFMPEG_HLS_TIME:2}
    hls-list-size: ${ISUP_FFMPEG_HLS_LIST_SIZE:6}
    hls-flags: ${ISUP_FFMPEG_HLS_FLAGS:delete_segments+append_list}
    mp4-args: ${ISUP_FFMPEG_MP4_ARGS:-f mp4 -movflags +frag_keyframe+empty_moov}
    log-level: ${ISUP_FFMPEG_LOG_LEVEL:error}

  session:
    start-timeout-sec: ${ISUP_SESSION_START_TIMEOUT_SEC:30}
    idle-timeout-sec: ${ISUP_SESSION_IDLE_TIMEOUT_SEC:120}
    download-max-sec: ${ISUP_SESSION_DOWNLOAD_MAX_SEC:3600}
```

### 配置项说明

| 配置项 | 必填 | 说明 |
|--------|------|------|
| `isup.public-ip` | 是 | 公网 IP，返回给设备用于回连。**不能是 0.0.0.0 / 127.0.0.1** |
| `isup.ehome-key` | 是 | ISUP 5.0 接入密钥，必须与录像机配置一致 |
| `isup.api-key` | 是 | ERP 调用接口的 `X-API-Key` |
| `isup.sdk-dir` | 是 | 海康 SDK 目录 |
| `isup.ffmpeg-path` | | ffmpeg 路径，默认 `ffmpeg` |
| `isup.media-dir` | | HLS 切片与 MP4 输出根目录 |
| `isup.cms.listen-ip/port` | | CMS 本地监听地址与端口，默认 `0.0.0.0:7660` |
| `isup.stream.listen-ip` | | 流媒体本地监听地址，默认 `0.0.0.0` |
| `isup.stream.preview-port` | | 预览监听端口，默认 `8003` |
| `isup.stream.playback-port` | | 回放/下载监听端口，默认 `8004` |
| `isup.link-mode` | | 0-TCP（公网固定用 0）、1-UDP、2-HRUDP |
| `isup.ffmpeg.codec-args` | | 默认 `-c copy` 不转码；若音频编码不被 MP4 支持可改成 `-c:v copy -c:a aac` |
| `isup.session.*` | | 各类超时时间（秒） |

---

## 7. 阿里云安全组端口

在**阿里云 ECS 安全组**（入方向）放行以下端口：

| 端口 | 协议 | 授权对象 | 用途 |
|------|------|----------|------|
| 7660 | TCP | 设备公网 IP 段（或 0.0.0.0/0） | CMS 注册 |
| 8003 | TCP | 设备公网 IP 段（或 0.0.0.0/0） | 实时预览推流 |
| 8004 | TCP | 设备公网 IP 段（或 0.0.0.0/0） | 回放 / 下载推流 |
| 8080 | TCP | ERP 服务器 IP（**不要对全网开放**） | HTTP API + HLS 播放 |

同时确认：

- ECS 内部防火墙（`firewalld` / `iptables`）也放行这些端口
- 若服务器在 NAT 之后（如靠端口映射），`public-ip` 必须填映射后的公网 IP，`listen-ip` 仍填 `0.0.0.0`
- HLS 播放地址走 8080 的 `/media/**`，浏览器必须能访问 8080

---

## 8. 海康录像机 ISUP 配置

在录像机 Web 管理界面（配置 → 网络 → 平台接入）：

| 配置项 | 取值 |
|--------|------|
| 平台接入方式 | **ISUP（原 EHome）** |
| 协议版本 | **ISUP 5.0**（务必选 5.0，不要选 4.0） |
| 服务器地址 | 阿里云**公网 IP** |
| 端口 | **7660** |
| DeviceID | 例如 `GW4206623`（后续 API 调用的 `deviceId`） |
| 密钥 | 与服务端 `isup.ehome-key` 完全一致 |
| 启用 | 勾选并保存 |

保存后观察：

1. 服务端日志出现 `设备上线：deviceId=..., lUserID=...`
2. `curl http://<服务器>:8080/api/v1/devices -H "X-API-Key: xxx"` 能看到该设备

设备通道号：模拟通道一般从 `1` 开始；IPC 接入 NVR 后可能是 `33`、`49` 等数字通道，可在录像机"通道管理"里查看。

---

## 9. ERP API 文档

所有 `/api/v1/**` 接口必须在请求头携带：

```
X-API-Key: <isup.api-key>
Content-Type: application/json
```

`/api/health` 不需要鉴权。

### 9.1 健康检查

`GET /api/health`（不鉴权）

```json
{
  "status": "UP",
  "sdkReady": true,
  "onlineDevices": 1,
  "activeSessions": 0,
  "publicIp": "47.xxx.xxx.xxx",
  "cmsPort": 7660,
  "previewPort": 8003,
  "playbackPort": 8004,
  "uptimeSeconds": 123
}
```

### 9.2 在线设备列表

`GET /api/v1/devices`

```json
[
  {
    "deviceId": "GW4206623",
    "lUserId": 0,
    "deviceIp": "1.2.3.4",
    "online": true,
    "onlineTime": "2026-09-19T17:20:31",
    "serialNumber": "GW4206623",
    "deviceName": ""
  }
]
```

### 9.3 实时预览

`POST /api/v1/preview`

请求：

```json
{
  "deviceId": "GW4206623",
  "channel": 1,
  "streamType": 0
}
```

- `streamType`：`0` 主码流 / `1` 子码流 / `2` 第三码流（默认 0）
- `linkMode`：可选，`0` TCP / `1` UDP / `2` HRUDP（默认取全局配置）

响应：

```json
{
  "sessionId": "0f7c8b1e-...",
  "type": "PREVIEW",
  "status": "STARTING",
  "playUrl": "/media/preview/0f7c8b1e-.../index.m3u8",
  "deviceId": "GW4206623",
  "channel": 1
}
```

浏览器直接用 `http://<服务器>:8080/media/preview/{sessionId}/index.m3u8` 播放（可配合 hls.js）。

### 9.4 按时间段回放

`POST /api/v1/playback`

请求：

```json
{
  "deviceId": "GW4206623",
  "channel": 1,
  "startTime": "2026-09-19T14:00:00",
  "endTime": "2026-09-19T14:10:00"
}
```

- 时间按**录像机设备本地时间**处理
- 支持 `yyyy-MM-dd'T'HH:mm:ss` 与 `yyyy-MM-dd HH:mm:ss` 两种格式
- 单次最大跨度 24 小时

响应：

```json
{
  "sessionId": "3a1e...",
  "type": "PLAYBACK",
  "status": "STARTING",
  "playUrl": "/media/playback/3a1e.../index.m3u8",
  "deviceId": "GW4206623",
  "channel": 1
}
```

### 9.5 按时间段下载录像（异步）

`POST /api/v1/download`

请求同回放。立即返回：

```json
{
  "taskId": "9c2f...",
  "status": "STARTING",
  "deviceId": "GW4206623",
  "channel": 1,
  "fileUrl": "/api/v1/download/9c2f.../file"
}
```

轮询状态：

`GET /api/v1/sessions/{taskId}`

```json
{
  "sessionId": "9c2f...",
  "type": "DOWNLOAD",
  "status": "COMPLETED",
  "deviceId": "GW4206623",
  "channel": 1,
  "filePath": "/opt/isup-server/data/media/download/9c2f.../GW4206623_ch1_....mp4",
  "downloadUrl": "/api/v1/download/9c2f.../file",
  "fileSize": 12345678,
  "startTime": "2026-09-19 14:00:00",
  "endTime": "2026-09-19 14:10:00"
}
```

状态取值：`STARTING` / `RUNNING` / `COMPLETED` / `FAILED` / `STOPPED`

下载文件：

`GET /api/v1/download/{taskId}/file` → 直接返回 MP4（需 `X-API-Key`）

### 9.6 查询会话状态

`GET /api/v1/sessions/{sessionId}`

### 9.7 停止会话 / 下载任务

`DELETE /api/v1/sessions/{sessionId}`

```json
{ "sessionId": "0f7c8b1e-...", "status": "STOPPED" }
```

停止时会依次释放：`NET_ESTREAM_StopPreview` / `NET_ECMS_StopGetRealStream`（预览）
或 `NET_ESTREAM_StopPlayBack` / `NET_ECMS_StopPlayBack`（回放下载），然后销毁 ffmpeg 进程并清理临时文件。

### 错误响应格式

```json
{
  "error": "ISUP_ERROR",
  "message": "NET_ECMS_StartPlayBack failed: deviceId=GW4206623 channel=1 sessionId=xxx errorCode=17",
  "operation": "NET_ECMS_StartPlayBack",
  "deviceId": "GW4206623",
  "channel": 1,
  "sessionId": "xxx",
  "errorCode": 17
}
```

参数类错误返回 `error=BAD_REQUEST`，资源不存在返回 `error=NOT_FOUND`，鉴权失败返回 401。

---

## 10. curl 调用示例

```bash
export SERVER=http://127.0.0.1:8080
export KEY=your-api-key
export DEV=GW4206623

# 1. 健康检查（不鉴权）
curl -s $SERVER/api/health | jq

# 2. 在线设备
curl -s -H "X-API-Key: $KEY" $SERVER/api/v1/devices | jq

# 3. 实时预览（主码流）
curl -s -X POST -H "X-API-Key: $KEY" -H "Content-Type: application/json" \
  -d '{"deviceId":"'"$DEV"'","channel":1,"streamType":0}' \
  $SERVER/api/v1/preview | jq

# 4. 按时间段回放
curl -s -X POST -H "X-API-Key: $KEY" -H "Content-Type: application/json" \
  -d '{"deviceId":"'"$DEV"'","channel":1,"startTime":"2026-09-19T14:00:00","endTime":"2026-09-19T14:10:00"}' \
  $SERVER/api/v1/playback | jq

# 5. 下载录像（异步）
TASK=$(curl -s -X POST -H "X-API-Key: $KEY" -H "Content-Type: application/json" \
  -d '{"deviceId":"'"$DEV"'","channel":1,"startTime":"2026-09-19 14:00:00","endTime":"2026-09-19 14:10:00"}' \
  $SERVER/api/v1/download | jq -r .taskId)
echo "taskId=$TASK"

# 6. 轮询下载状态
curl -s -H "X-API-Key: $KEY" $SERVER/api/v1/sessions/$TASK | jq

# 7. 下载 MP4
curl -s -H "X-API-Key: $KEY" -o record.mp4 $SERVER/api/v1/download/$TASK/file

# 8. 停止预览 / 回放
curl -s -X DELETE -H "X-API-Key: $KEY" $SERVER/api/v1/sessions/$TASK | jq
```

---

## 11. 本地快速启动

配置统一放在 **`config/application.yml`**（Spring Boot 会自动加载，优先级高于 jar 内配置），
改配置不需要重新打包。该文件含密钥，已加入 `.gitignore`，模板见 `config/application.example.yml`。

```bash
# 1. 复制模板（如还没有）
cp config/application.example.yml config/application.yml

# 2. 改这三个值
#    isup.public-ip   公网 IP（部署到阿里云时填 ECS 的公网 IP）
#    isup.ehome-key   与录像机「平台接入」里一致的密钥
#    isup.api-key     ERP 调用用的 X-API-Key

# 3. 构建
mvn clean package -DskipTests

# 4. 启动
./start.sh          # Linux / macOS
start.bat           # Windows
```

启动成功的标志：日志出现 `海康 ISUP SDK 加载完成` + `CMS 注册监听成功` + `预览监听成功` + `回放/下载监听成功`。

如果只看到 `ISUP 服务启动失败` 的提示块，按里面的 4 条清单逐项核对即可（最常见是 SDK 未放齐、public-ip 填错）。

## 12. Linux 部署方式

### 12.1 构建

```bash
mvn clean package -DskipTests
# 产物：target/isup-server-1.0.0.jar
```

### 12.2 目录规划

```bash
sudo mkdir -p /opt/isup-server
cd /opt/isup-server

# 放入 jar 与海康 SDK
cp target/isup-server-1.0.0.jar /opt/isup-server/
cp -r /path/to/hikvision-sdk/* /opt/isup-server/sdk/
mkdir -p /opt/isup-server/data/media /opt/isup-server/logs
```

目录结构：

```
/opt/isup-server/
├── isup-server-1.0.0.jar
├── sdk/
│   ├── libHCISUPCMS.so
│   ├── libHCISUPStream.so
│   ├── libcrypto.so
│   ├── libssl.so
│   └── HCAapSDKCom/
├── data/media/          # HLS 切片与下载 MP4
└── logs/EHomeSDKLog/    # 海康 SDK 日志
```

### 12.3 启动前配置（环境变量）

```bash
export ISUP_PUBLIC_IP=47.xxx.xxx.xxx      # 阿里云公网 IP（必填）
export ISUP_EHOME_KEY=xxxxxx              # 与录像机一致的密钥（必填）
export ERP_API_KEY=xxxxxx                 # ERP 调用密钥（必填）
export ISUP_SDK_DIR=/opt/isup-server/sdk  # SDK 目录（必填）
export FFMPEG_PATH=/usr/bin/ffmpeg        # ffmpeg 路径
export ISUP_MEDIA_DIR=/opt/isup-server/data/media
```

### 12.4 直接启动（不使用 Docker）

```bash
cd /opt/isup-server
java -jar isup-server-1.0.0.jar
```

### 12.5 systemd 托管（推荐）

创建 `/etc/systemd/system/isup-server.service`：

```ini
[Unit]
Description=Hikvision ISUP 5.0 Server
After=network.target

[Service]
Type=simple
User=root
WorkingDirectory=/opt/isup-server
Environment="ISUP_PUBLIC_IP=47.xxx.xxx.xxx"
Environment="ISUP_EHOME_KEY=xxxxxx"
Environment="ERP_API_KEY=xxxxxx"
Environment="ISUP_SDK_DIR=/opt/isup-server/sdk"
Environment="FFMPEG_PATH=/usr/bin/ffmpeg"
Environment="ISUP_MEDIA_DIR=/opt/isup-server/data/media"
ExecStart=/usr/bin/java -jar /opt/isup-server/isup-server-1.0.0.jar
Restart=on-failure
RestartSec=10
StandardOutput=append:/opt/isup-server/logs/stdout.log
StandardError=append:/opt/isup-server/logs/stderr.log

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable isup-server
sudo systemctl start isup-server
sudo systemctl status isup-server
sudo journalctl -u isup-server -f
```

### 12.6 可选：Nginx 反代 8080（给 ERP / 浏览器用）

```nginx
server {
    listen 80;
    server_name isup.example.com;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_read_timeout 300s;
    }
}
```

> 7660 / 8003 / 8004 是设备直连端口，**不能**走 Nginx 反代。

---

## 13. 常见错误排查

### 13.1 设备一直不在线

| 现象 | 排查 |
|------|------|
| 日志无任何 `设备注册状态回调` | 7660 端口未放行（安全组 / firewalld）；录像机服务器地址填错 |
| `ISUP 密钥校验失败` | `isup.ehome-key` 与录像机密钥不一致 |
| `SessionKey 交互异常` | 录像机协议版本不是 ISUP 5.0，改成 5.0 |
| `NET_ECMS_StartListen 失败` | 7660 被占用：`ss -lntp \| grep 7660` |
| 设备显示"注册失败/超时" | `public-ip` 填成了 0.0.0.0 / 127.0.0.1，或 NAT 环境未填映射后的公网 IP |

### 13.2 预览返回 `设备回连超时`

- 8003 端口未放行 → 设备连不上来推流
- `public-ip` 不是设备可访问的地址
- ffmpeg 未安装或 `ffmpeg-path` 配置错误（日志会有 `启动 ffmpeg 失败`）
- 通道号填错（NVR 下 IPC 常是 33/49 等，不是 1）

### 13.3 预览有 sessionId，但播放 404 / 黑屏

- 等 2~5 秒再拉 `index.m3u8`（HLS 需要先产生切片）
- 用 `GET /api/v1/sessions/{sessionId}` 确认状态是 `RUNNING`（`STARTING` 说明设备还没推流）
- 浏览器访问 8080 端口是否可达
- 查看 `data/media/preview/{sessionId}/` 下是否生成了 `index.m3u8` 与 `seg_*.ts`

### 13.4 回放/下载一直 STARTING

- 8004 端口未放行
- 该时间段确实没有录像（换一个已知有录像的时间段）
- 通道号错误

### 13.5 下载任务 FAILED

- 看日志里 `NET_ECMS_StartPlayBack failed: ... errorCode=xx`
- MP4 封装不支持设备音频编码时，把 `isup.ffmpeg.codec-args` 改成 `-c:v copy -c:a aac`
- 时间跨度过大被拒绝（单次上限 24 小时）

### 13.6 服务启动失败

| 日志 | 原因 |
|------|------|
| `缺少海康 SDK 文件 xxx` | SDK 目录不完整或 `ISUP_SDK_DIR` 指错 |
| `加载库 ... 错误` | SDK 版本不匹配 / 缺少 `HCAapSDKCom` / 不是 x86_64 |
| `必须配置 isup.public-ip` | 未设置 `ISUP_PUBLIC_IP` |
| `isup.public-ip 不能是 0.0.0.0` | 公网地址填了监听地址 |
| `ffmpeg 不可用` | `FFMPEG_PATH` 不对，媒体功能不可用（接口仍可调用） |

### 13.7 日志位置

- 应用日志：控制台 / `stdout.log`
- 海康 SDK 日志：`./logs/EHomeSDKLog/`（`NET_ECMS_SetLogToFile` 与 `NET_ESTREAM_SetLogToFile` 输出）

---

## 14. 构建与测试

```bash
# 编译（Java 21）
mvn clean compile

# 单元测试（不依赖真实海康 SDK）
mvn test

# 打包
mvn clean package -DskipTests
```

单元测试覆盖：参数校验、会话管理（状态机 / 防重复停止 / 会话 ID 关联）、时间范围校验、API Key 鉴权、文件路径与穿越防护、FFmpeg 命令构建、回放入参构造、设备注册表。

> 真实 ISUP SDK 需要 native library 和真实设备，CI 中不连接设备。

---

## 15. 并发与资源释放说明

1. 同一设备允许多个不同通道同时预览，会话用 `ConcurrentHashMap` 管理
2. JNA 回调对象由会话与 newlink 监听器**双重强引用**持有，不会被 GC
3. `sessionId`（本服务 UUID）与 ISUP SDK `lSessionID` 建立双向索引；回放以设备实际会话 ID 为准
4. 预览与回放使用不同的监听端口与会话类型，不混用
5. 服务关闭依次执行：停止全部会话 → `StopListenPreview` → `StopListenPlayBack` → `StopListen` → `NET_ESTREAM_Fini` → `NET_ECMS_Fini`
6. 会话停止用 `AtomicBoolean` 保证不会重复 stop
7. ffmpeg 进程在会话结束时先关闭 stdin、再等待收尾、最后强制销毁
8. 下载失败会清理临时文件；下载成功保留 MP4
