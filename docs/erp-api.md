# ERP 接口（设备列表 / 录像下载）

Base：`http://127.0.0.1:8080`（部署时换成实际地址）<br>
鉴权：所有 `/api/v1/**` 请求头必须带 `X-API-Key`（与服务端 `isup.api-key` 一致）。<br>
时间一律按**录像机本地时间**，不要转 UTC。

以下请求/响应为 2026-09-20 本机实测。Key 用占位符，不要把真实密钥写进文档。

---

## 1. 查询设备列表

`GET /api/v1/devices`

无 query、无 body。内存列表，设备未注册上来则为 `[]`。<br>
`online=false` 表示刚掉线、还在宽限期内，条目仍会返回。

**请求**

```http
GET /api/v1/devices HTTP/1.1
Host: 127.0.0.1:8080
X-API-Key: <api-key>
```

**响应 `200`**

```http
HTTP/1.1 200
Content-Type: application/json
```

```json
[
  {
    "deviceId": "GW9806925",
    "lUserId": 1,
    "deviceIp": "192.168.22.211",
    "online": true,
    "onlineTime": "2026-09-20T15:12:41.5413441",
    "serialNumber": "GW9806924",
    "deviceName": "",
    "luserId": 1
  },
  {
    "deviceId": "DLWH1xx",
    "lUserId": 0,
    "deviceIp": "192.168.22.210",
    "online": true,
    "onlineTime": "2026-09-20T15:25:23.8013501",
    "serialNumber": "GW4206623",
    "deviceName": "",
    "luserId": 0
  }
]
```

| 字段 | 说明 |
|------|------|
| deviceId | 设备编号，下载时原样传入 |
| online | `true` 在线 / `false` 掉线宽限期 |
| deviceIp | 设备注册时上报的 IP，仅展示，会随 DHCP/重注册变化。调接口用 `deviceId` |
| serialNumber | 设备序列号，可能和 `deviceId` 不同 |
| lUserId / luserId | SDK 登录句柄，调用方忽略即可（Jackson 重复序列化） |

**通道号本接口不返回**（设备注册信息里没有通道数），需要通道号请调 [1.2 查询设备通道](#12-查询设备通道)。

---

## 1.2 查询设备通道

`GET /api/v1/devices/{deviceId}/channels`

仅通过 `NET_ECMS_ISAPIPassThrough` 查询设备端通道号与名称，不回退设备 HTTP。
透传按 SDK 返回的 `dwOutSize` / `dwReturnedXMLLen`
读取有效报文，并检查容量与空响应；仅 `dwReturnedXMLLen=0` 不能认定设备不支持透传。

响应按设备通道名称末尾的“全景 / 细节”分组成打包台，示例：

```json
{
  "serialNumber": "GW4206623",
  "channels": [
    {"channelCode": "打包台1", "panoramaChannel": 1, "detailChannel": 2}
  ]
}
```

`channelCode` 用于匹配出库记录的 `channel_code`；两个通道号可用于下载。
名称不符合工位规则的通道不进入分组；某一角色缺失或重名时，该通道号为 null。
查询到真实通道但没有符合规则的工位时，分组仍可能为空，不能据此断定设备没有通道。

两条查询路径都未取得有效列表时返回 `500 / ISUP_ERROR`，不再将异常包装成成功空列表。
响应的 `message` 包含透传失败原因；设备离线及请求参数错误保留原有异常处理。
录像存在性检查使用下面的 `/channels/detect`，它不等同于完整通道枚举。

### 1.2.1 通道探测（透传不可用时的兜底）

`POST /api/v1/devices/{deviceId}/channels/detect`

对 `1..maxChannel` 每个通道回放请求里那一小段录像，收到首帧即判定该通道有录像。

```json
{"startTime": "2026-09-21 09:53:06", "endTime": "2026-09-21 09:53:21", "maxChannel": 4}
```

| 字段 | 必填 | 说明 |
|------|------|------|
| startTime / endTime | 是 | 探测用的一小段录像时间，选**确定有录像**的时段 |
| maxChannel | 否 | 默认 8，上限 32 |

响应给出 `availableChannels`（可直接用于下载的 `channel`）与逐通道明细：

```json
{
  "deviceId": "DLWH1xx",
  "availableChannels": [1, 2],
  "probes": [
    {"channel": 1, "hasRecording": true,  "message": "收到录像数据", "name": "全景"},
    {"channel": 3, "hasRecording": false, "message": "无数据（通道不存在或该时段无录像）", "name": ""}
  ]
}
```

判定的是**该时段有没有录像**：某通道当时没录也会被判为无数据，换一段有录像的时间再试。
探测仅通过 ISUP 检索录像，不再为获取名称调用 HTTP。`name` 不填设备名称，不虚构工位映射。

### 1.2.2 降级参数 `probeMinutes`

需要在通道查询失败后降级检索录像时，可带上该参数再调一次，
服务端自动改为「回放最近 N 分钟」逐通道探测并直接返回通道号（N 取值 1~1440）：

```http
GET /api/v1/devices/DLWH1xx/channels?probeMinutes=120 HTTP/1.1
X-API-Key: <api-key>
```

响应里的 `source` 会以 `probe:` 开头，标明结果是探测得来的。

## 1.3 ISAPI 代理（探针）

`POST /api/v1/devices/{deviceId}/isapi`

向设备发一条任意 ISAPI 请求，原样返回响应 XML。对接设备新能力（叠加、抓图、参数）时
**先用它摸清这台设备实际支持的路径和真实报文结构**，再决定怎么封装，不要凭型号猜。

> **仅走设备已注册的 ISUP 会话。** 字幕、通道查询和通用 ISAPI 使用同一透传客户端，不依赖设备 IP:80 或网页登录账密。

**请求**

```http
POST /api/v1/devices/DLWH1xx/isapi HTTP/1.1
Host: 127.0.0.1:8080
X-API-Key: <api-key>
Content-Type: application/json
```

```json
{
  "method": "GET",
  "path": "/ISAPI/System/Video/inputs/channels/1/overlays"
}
```

| 字段 | 必填 | 说明 |
|------|------|------|
| method | 否 | `GET` / `PUT` / `POST`，默认 `GET` |
| path | 是 | ISAPI 全路径，必须以 `/ISAPI/` 开头，不含空白或控制字符 |
| body | 否 | 请求体 XML，`PUT`/`POST` 时填 |
| timeoutMs | 否 | 本次 SDK 接收超时；默认取 `isup.isapi.timeout-ms`，未配置时兼容 `isup.device-auth.timeout-ms`，均未配置则 5000 ms，有效范围会限制到 1000~60000 ms |

服务端通过 ISUP 透传 GET、PUT 或 POST；请求体按 XML 声明编码。SDK 失败、空响应或长度越界返回错误，不自动重发写请求。通用代理原样返回设备 XML，HTTP 200 仅表示取得报文；设备是否接受须检查 XML 中的 `statusCode`。写入超时可能已经生效，先读取配置确认，不直接重发。

**响应 `200`**

```json
{
  "deviceId": "DLWH1xx",
  "requestLine": "GET /ISAPI/System/Video/inputs/channels/1/overlays",
  "responseLength": 1536,
  "xml": "<?xml version=\"1.0\" encoding=\"GBK\"?><VideoOverlay>...</VideoOverlay>"
}
```

`xml` 按设备声明的 UTF-8 / GBK / GB2312 字符集解码；中文应可正常阅读。`responseLength` 是解码后文本的 UTF-8 字节数。

---

## 1.4 工位单号叠加（OSD）

`POST /api/v1/devices/{deviceId}/overlay`

按设备通道名称匹配工位，例如 `打包台1` 对应 `打包台1全景` 和 `打包台1细节`。
两路均通过 ISUP 读取 overlays、整组改写单号、平台订单号和收件地址后 PUT 回同一条路径，不使用设备 HTTP。
同一 ISUP 实例中，同一设备、同一工位的通道查询和两路写入整体排队执行，避免并发请求交叉写入全景和细节；不同工位互不阻塞。此顺序以请求进入工位锁的先后为准，上游未提供业务序号时不能识别更晚到达的旧业务请求；多实例同时写同一工位也不在本地锁范围内。

```json
{
  "channelCode": "打包台1",
  "waybillNo": "TEST123456789",
  "platformOrderNo": "321123456789",
  "recipient": {
    "waybillNo": "TEST123456789",
    "applyRecordId": "对应的成功申请记录ID",
    "name": "EXAMPLE NAME",
    "address": "100 DEMO STREET BUILDING B UNIT 502",
    "district": "",
    "city": "LOS ANGELES",
    "state": "CA",
    "postcode": "90001",
    "country": "US"
  }
}
```

| 字段 | 说明 |
|---|---|
| channelCode | 必填工位名称；空值、`undefined`、`null` 均拒绝，HTTP 400 |
| waybillNo | 单号；空字符串清除整组业务字幕，只保留「快递单号：」前缀 |
| platformOrderNo | 当前包裹的 `lms_waybill.platform_order_no`；独立于收件地址传递，缺失时省略该行，不以 OMS 内部单号或物流订单号代替 |
| recipient | ERP 核验当前单号对应申请后，从冻结请求提取的收件资料；不得用 OMS 当前地址补造。缺失时清理旧地址，显示「地址未获取」 |
| recipient.waybillNo / applyRecordId | 必须与外层单号一致，并提供对应申请记录 ID；地址和国家不能为空 |
| expectedWaybillNo | 出库清除携带原单号；与画面单号不一致时跳过，避免清除下一包裹 |

出库后请求示例：`{"channelCode":"打包台1","waybillNo":"","expectedWaybillNo":"TEST123456789"}`。
ERP 复用原有异步队列，按当前租户、仓库、面单 ID 和单号查询一次冻结申请地址，随后与单号、平台订单号一起透传。查询时恢复请求租户，结束后还原线程上下文。地址缺失或查询异常时仍发送当前单号，ISUP 清除旧地址并显示“地址未获取”。
出库清除沿用原事务提交后的调用，清除当前选择工位的全景和细节业务字幕。字幕查询及下发失败不修改打包、称重、库存或出库状态；不引入 Redis 字幕任务、自动换单刷新、后台重试或页面轮询。换单后重新打开打包窗口读取当前面单地址。
申请地址读取同时核对租户、仓库、当前号码、申请记录、阶段、物流身份、成功结果和号码快照；历史无证据、人工导入或申请未完成时不回退订单最新地址。
普通华磊延迟补号还会核对同一申请的补号成功记录及已保存的物流商响应号码；区县按当次 HL、DH、JS 请求字段还原。DH、JS 的独立门牌字段以“门牌：”追加到完整街道后，不把路线或单元里的相同数字视为重复门牌；华磊已拼好的完整地址保持原值。

**成功响应**：`{"code":200,"msg":"成功"}`，沿用原接口格式。

缺少通道、任一路读取失败、设备拒绝或不能确认写入结果时返回 HTTP 500、`error=ISUP_ERROR`，
`message` 给出工位和失败通道原因。两路独立执行，可能一路已成功、另一路失败；调用方不得把错误响应当成两路均未写入。
SDK 超时不触发 HTTP 回退或换 ISAPI 路径重写。再次写入前应先通过 `/isapi` 的 GET 核对各通道现状。
设备明确返回完整 `ResponseStatus` 且 `statusCode=1` 后，显示请求还要回读确认整组文字、启用状态、位置及系统水印的时间格式；设备截断、回读失败均不能报告完整显示成功。
清除请求只核验业务块：1 号块开启且仅有「快递单号：」，2~8 号块关闭且文字为空（不存在的块视为空）。清除保留时间及通道名原配置，设备规范化坐标、系统块缺失或时间格式不同均不影响清除结果；任何旧单号、平台号、地址残留都不能报告清除成功。
读取到的配置本来就满足同一验收规则时跳过 PUT；即使 XML 完全相同，缺系统水印块的显示请求也不能报告成功。清除请求因单号不匹配被跳过时不写设备。
设备回执不等于人工看图验收。

**读写规则**：读取时依次尝试 `/ISAPI/System/Video/inputs/channels/{channel}/overlays` 和
`/ISAPI/ContentMgmt/InputProxy/channels/{channel}/overlays`；取到有效配置后，只向该地址写一次。
打包工位接口管理 overlay 1~8：单号和前缀放在 1，平台订单号、收件人、地址、地区、邮编、国家按需使用 2~8；每次清除多余旧行。
第一行同样检查完整前缀与单号的字符集、字节数、可见宽度及 XML 字符；单号无法完整显示时只保留固定前缀并清掉旧业务字幕，返回失败原因，不用替换字符或截断号码。排版失败与设备写入/回读失败同时保留。
显示时开启时间及通道名，日期格式为 `CHR-YYYY-MM-DD`、时间为 `24hour`、关闭星期显示，通道名文字沿用设备配置。已有系统水印块内缺少约定字段时补齐，只改写及验收直属字段，保留命名空间、属性和设备扩展内的同名字段；整块缺失时仍更新当前业务字幕以移除上一单内容，但返回未确认完整显示，不猜建设备系统结构。
显示时，时间、通道名、单号固定使用纵坐标 544 / 512 / 480，其余业务行从 448 起连续排列，全部使用 32 的统一行距，不插空行。为补偿设备时间水印自带的左边距，时间横坐标固定为 16，通道名及全部业务字幕横坐标固定为 32，目标为画面左边缘视觉对齐；此数值为设备坐标单位，不等同屏幕像素。即使设备原坐标曾被人工调整，打包显示请求也会恢复为这套固定排版；实际视觉效果须在全景、细节画面分别验收。
平台订单号与地址均完整换行，英文优先按空格换行，中文按显示宽度换行，同时检查 XML 声明字符集及每行字节数。地址超出行数或无法编码时尝试保留平台订单号、清理旧地址、显示缺失提示并返回失败，不静默截断；平台订单号本身无法完整排入时继续降级，移除平台号并清理旧地址，保留当前单号及可容纳的缺失提示，仍返回失败。
地区按区县、市、省顺序拼接，仅对清理空白后完全相同的字段忽略大小写去重，保留首次写法，不删除子串或改动街道地址。邮编和国家在完整合行不超过显示宽度及字节上限时合并，用两个空格分隔；超限则分别换行，邮编为空时只显示国家。
总行数超出容量时，仅尝试把最后一行地区与完整邮编/国家尾行合并；只有完整候选同时满足行宽、字符集和字节限制才使用，不删减任何字段。

普通地址最终排版示例（每行视觉左对齐、相邻行间距固定为 32）：

```text
2026-10-14 15:30:18
打包台1全景
快递单号：YLUS0778515392
平台订单号：321123456789
收件人：Jane Doe
地址：100 Demo Road Apt 2
地区：Los Angeles, CA
邮编：90001  国家：US
```

合法的空 `displayText` 和自闭合 `TextOverlayList` 可以正常清除及重新显示；新增字符块放入唯一的 `TextOverlayList` 内，保留设备的命名空间与属性。列表重复、字符块位于列表外或嵌套层级异常时返回失败，写入目标和回读均执行相同校验。
`TextOverlayList.size` 存在时不超过其声明数量，最多使用 8 块；该配置不是所有型号的能力保证，部署前应读取设备配置、核实可用字符块并进行画面验收。此接口的 1~8 号块应专用于打包业务。
工位接口默认不自动清空。内部叠加服务支持的到期恢复前缀也使用 ISUP，写入失败后不换路径重发。

| 配置 | 默认 | 说明 |
|---|---|---|
| `isup.overlay.skip-unchanged` | `true` | 读取配置与目标相同时跳过 PUT |
| `isup.overlay.min-write-interval-sec` | `3` | 同一设备同一通道的最小写入间隔，`0` 表示不限流 |
| `isup.overlay.address-line-width` | `44` | 地址每行显示宽度，ASCII 计 1，其他字符计 2；根据设备字号校准 |
| `isup.overlay.address-line-max-bytes` | `64` | 每行编码字节上限，根据设备能力校准；不会截断地址 |

升级顺序：先部署支持 recipient 和 platformOrderNo 的 ISUP，再部署 ERP；旧版 ISUP 忽略新字段时不能作为地址或平台订单号功能验收。生产验收须同时核对实际面单、包裹对应的平台订单、两路实时画面及录像。冻结请求证明提交给物流商的地址，不替代物流商最终面单文件的抽样核对。

**前置条件**：ISUP SDK 就绪、设备已注册并有有效会话。旧 `isup.device-auth` 配置保留兼容绑定，
字幕、通道查询与通用代理不再读取其凭据；默认超时优先取 `isup.isapi.timeout-ms`，缺省时兼容旧 `isup.device-auth.timeout-ms`（默认 5000 ms）。既有 `/channels/{channel}/overlay` 示例不对应当前公开接口，
工位业务使用本节接口；单通道诊断通过通用 ISAPI 代理。

**排查顺序**：先 GET 两路 overlays，检查响应内容和 SDK 错误码，再判断是否需要写入。
`dwReturnedXMLLen=0` 本身不能证明设备不支持透传，必须同时检查 `dwOutSize` 和返回缓冲区。
OSD 字符叠加与设备扫码枪触发的码头信息功能是不同功能。

---

## 2. 录像下载（异步）

流程：创建任务 → 轮询状态 → `COMPLETED` 后拉 MP4。<br>
下面用 `DLWH1xx` 通道 1，下载 `15:20:00`～`15:21:00` 共 1 分钟。

### 2.1 创建任务

`POST /api/v1/download`

**请求**

```http
POST /api/v1/download HTTP/1.1
Host: 127.0.0.1:8080
X-API-Key: <api-key>
Content-Type: application/json
```

```json
{
  "deviceId": "DLWH1xx",
  "channel": 1,
  "startTime": "2026-09-20 15:20:00",
  "endTime": "2026-09-20 15:21:00"
}
```

| 字段 | 必填 | 说明 |
|------|------|------|
| deviceId | 是 | 来自设备列表 |
| channel | 是 | ≥ 1 |
| startTime / endTime | 是 | `yyyy-MM-dd HH:mm:ss` 或 `yyyy-MM-dd'T'HH:mm:ss`；start < end；单次最长 24 小时 |
| linkMode | 否 | `0` TCP / `1` UDP / `2` HRUDP，不传跟服务配置 |

**响应 `200`**（立刻返回，不阻塞等文件）

```http
HTTP/1.1 200
Content-Type: application/json
```

```json
{
  "taskId": "29dfd8d4-3454-4dc3-aa7f-afdcadccf38c",
  "status": "STARTING",
  "deviceId": "DLWH1xx",
  "channel": 1,
  "fileUrl": "/api/v1/download/29dfd8d4-3454-4dc3-aa7f-afdcadccf38c/file"
}
```

### 2.2 查询任务

`GET /api/v1/sessions/{taskId}`

建议 2～5 秒轮询一次。本任务约 30 秒从 `RUNNING` 到 `COMPLETED`。

| status | 含义 | 调用方 |
|--------|------|--------|
| STARTING | 等待设备推流 | 继续轮询 |
| RUNNING | 正在写 MP4 | 继续轮询 |
| COMPLETED | 完成 | 用 2.3 拉文件 |
| FAILED | 失败，看 `error` | 停 |
| STOPPED | 被取消 | 停 |

**请求**

```http
GET /api/v1/sessions/29dfd8d4-3454-4dc3-aa7f-afdcadccf38c HTTP/1.1
Host: 127.0.0.1:8080
X-API-Key: <api-key>
```

**响应 `200`（进行中）**

```json
{
  "sessionId": "29dfd8d4-3454-4dc3-aa7f-afdcadccf38c",
  "type": "DOWNLOAD",
  "status": "RUNNING",
  "deviceId": "DLWH1xx",
  "channel": 1,
  "filePath": "D:\\hiki\\hiki-1\\data\\media\\download\\29dfd8d4-3454-4dc3-aa7f-afdcadccf38c\\DLWH1xx_ch1_20260920152000-20260920152100.mp4",
  "downloadUrl": "/api/v1/download/29dfd8d4-3454-4dc3-aa7f-afdcadccf38c/file",
  "fileSize": 1048608,
  "bytesReceived": 1596364,
  "startTime": "2026-09-20 15:20:00",
  "endTime": "2026-09-20 15:21:00",
  "createdAt": "2026-09-20 15:34:37",
  "updatedAt": "2026-09-20 15:34:37"
}
```

**响应 `200`（完成）**

```json
{
  "sessionId": "29dfd8d4-3454-4dc3-aa7f-afdcadccf38c",
  "type": "DOWNLOAD",
  "status": "COMPLETED",
  "deviceId": "DLWH1xx",
  "channel": 1,
  "filePath": "D:\\hiki\\hiki-1\\data\\media\\download\\29dfd8d4-3454-4dc3-aa7f-afdcadccf38c\\DLWH1xx_ch1_20260920152000-20260920152100.mp4",
  "downloadUrl": "/api/v1/download/29dfd8d4-3454-4dc3-aa7f-afdcadccf38c/file",
  "fileSize": 1048608,
  "bytesReceived": 1596364,
  "startTime": "2026-09-20 15:20:00",
  "endTime": "2026-09-20 15:21:00",
  "createdAt": "2026-09-20 15:34:37",
  "updatedAt": "2026-09-20 15:35:08"
}
```

默认最长跑 3600 秒；完成后文件大约保留 60 分钟，超时会被清掉。<br>
`filePath` 是服务端本地路径，调用方用 `downloadUrl`，不要直接读这个盘路径。

### 2.3 取 MP4

`GET /api/v1/download/{taskId}/file`<br>
同样要带 `X-API-Key`。`status` 必须是 `COMPLETED`，否则 400。响应体是二进制，下面只贴响应头。

**请求**

```http
GET /api/v1/download/29dfd8d4-3454-4dc3-aa7f-afdcadccf38c/file HTTP/1.1
Host: 127.0.0.1:8080
X-API-Key: <api-key>
```

**响应 `200`**

```http
HTTP/1.1 200
Content-Disposition: attachment; filename="DLWH1xx_ch1_20260920152000-20260920152100.mp4"
Content-Type: video/mp4
Content-Length: 1048608
Accept-Ranges: bytes
```

### 2.4 取消（可选）

`DELETE /api/v1/sessions/{taskId}`

```json
{ "sessionId": "29dfd8d4-3454-4dc3-aa7f-afdcadccf38c", "status": "STOPPED" }
```

---

## 错误

| HTTP | error | 常见原因 |
|------|-------|----------|
| 401 | UNAUTHORIZED | 缺或错 `X-API-Key` |
| 400 | BAD_REQUEST | 参数非法、设备不在列表、任务未完成就拉文件 |
| 404 | NOT_FOUND | taskId 不存在，或文件已被清理 |
| 500 | ISUP_ERROR | 设备/SDK 取流失败，看 `message`、`errorCode` |

缺 Key 实测见上面设备列表。

---

## 调用示例

```bash
KEY=你的api-key
BASE=http://127.0.0.1:8080

curl --noproxy '*' -s -H "X-API-Key: $KEY" $BASE/api/v1/devices

TASK=$(curl --noproxy '*' -s -X POST -H "X-API-Key: $KEY" -H "Content-Type: application/json" \
  -d '{"deviceId":"DLWH1xx","channel":1,"startTime":"2026-09-20 15:20:00","endTime":"2026-09-20 15:21:00"}' \
  $BASE/api/v1/download | jq -r .taskId)

curl --noproxy '*' -s -H "X-API-Key: $KEY" $BASE/api/v1/sessions/$TASK

curl --noproxy '*' -s -H "X-API-Key: $KEY" -o record.mp4 $BASE/api/v1/download/$TASK/file
```

---
