# 前端回归测试

## 页面与部署

前端仍由 Spring Boot 的 `static` 目录提供，入口保持 `/debug.html`，不新增 Node 构建或运行时前端框架。HTML、样式、交互和公共工具分别维护于 `debug.html`、`debug/console.css`、`debug/console.js` 和 `debug/console-utils.js`。升级时一起发布这四个文件；只替换 HTML 会缺失样式和模块。

页面采用 JeecgBoot / Ant Design 风格的后台布局，不依赖 JeecgBoot 前端运行时。预览、回放、下载和会话操作继续调用现有接口，不修改 Java 服务或设备配置。

API Key 默认保存在当前标签页的 sessionStorage；只有明确勾选「在此浏览器记住 API Key」才写入 localStorage。旧版已记住的 Key 继续兼容，可取消勾选后保存来清理持久值。浏览器存储并非安全保险箱，请勿在共享电脑上记住 Key。本页适合受控内网使用；接口鉴权、跨域、`/media/**` 的访问策略仍由后端和反向代理负责。

## 工具函数与请求层测试

需要 Node.js 22，不安装 npm 依赖：

```bash
node --experimental-default-type=module --test tests/frontend/console-utils.test.mjs
node --experimental-default-type=module --check src/main/resources/static/debug/console.js
node --experimental-default-type=module --check src/main/resources/static/debug/console-utils.js
```

覆盖地址校验、设备本地时间、文件名处理、存储兼容、请求头鉴权、连接快照、超时、取消和二进制响应。

## 浏览器交互测试

在仓库根目录执行：

```bash
python -m pip install playwright
python -m playwright install chromium
python tests/frontend/test_console.py
```

测试脚本启动临时静态服务，通过 Playwright 拦截接口并使用确定性测试数据，不连接真实录像机。覆盖导航、320–1920 像素布局、设备搜索、动态文本安全渲染、输入校验、播放切换、停止失败保护、下载、会话筛选分页、停止确认、401、配置存储和 HLS 组件加载失败。设置 `SCREENSHOT_DIR` 可以输出测试截图。

已有 Chromium 时，可用 `CHROMIUM_EXECUTABLE` 指定浏览器路径。受限环境无法导航本地地址时，可设置 `OFFLINE_BROWSER=1`：以 `set_content` 加载同一份 HTML/CSS/JS，替换网络、存储和下载传输层，在内存中验证页面交互。例如：

```bash
OFFLINE_BROWSER=1 CHROMIUM_EXECUTABLE=/usr/bin/chromium SCREENSHOT_DIR=/tmp/isup-ui python tests/frontend/test_console.py
```

离线模式不覆盖浏览器原生 ES Module 网络加载、HTTP 静态资源投递、真实跨域和浏览器下载文件落盘；模块本身通过 Node 测试。模拟 HLS 只验证加载、切换和销毁时机，不验证真实流解码或设备推流。

## 真实环境验收

部署后访问 `/debug.html`，核对静态资源加载和实际服务地址；使用内网 API Key 依次测试在线设备、预览、指定时间回放、停止、MP4 下载。核对录像机时间、播放端口和浏览器编码兼容性。真实 SDK、录像机、转码和网络链路需要在有设备的环境中另行验收，不能用模拟测试结果替代。
