"""浏览器回归测试：使用模拟接口，不需要 ISUP SDK、录像机或后端进程。"""
import functools
import json
import os
import re
import threading
import unittest
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from playwright.sync_api import expect, sync_playwright

ROOT = Path(__file__).resolve().parents[2]
STATIC = ROOT / 'src/main/resources/static'
OFFLINE = os.getenv('OFFLINE_BROWSER') == '1'
HLS_STUB = """
window.hlsAttached = 0; window.hlsDestroyed = 0;
window.Hls = class {
  static Events = { MANIFEST_PARSED: 'manifest', ERROR: 'error' };
  static isSupported() { return true; }
  constructor() { this.handlers = {}; }
  on(name, handler) { this.handlers[name] = handler; }
  loadSource(url) { window.lastHlsUrl = url; }
  attachMedia() { window.hlsAttached++; this.handlers.manifest?.(); }
  destroy() { window.hlsDestroyed++; }
};
"""


class QuietHandler(SimpleHTTPRequestHandler):
    def log_message(self, *_args):
        pass


class ConsoleTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        handler = functools.partial(QuietHandler, directory=str(STATIC))
        cls.server = ThreadingHTTPServer(('127.0.0.1', 0), handler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.origin = f'http://127.0.0.1:{cls.server.server_port}'
        cls.playwright = sync_playwright().start()
        options = {'headless': True}
        if os.getenv('CHROMIUM_EXECUTABLE'):
            options['executable_path'] = os.environ['CHROMIUM_EXECUTABLE']
        cls.browser = cls.playwright.chromium.launch(**options)

    @classmethod
    def tearDownClass(cls):
        cls.browser.close()
        cls.playwright.stop()
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join()

    def setUp(self):
        self.context = self.browser.new_context(viewport={'width': 1440, 'height': 1050}, accept_downloads=True)
        self.context.add_init_script("HTMLMediaElement.prototype.canPlayType = () => '';")
        self.page = self.context.new_page()
        self.page.set_default_timeout(5000)
        self.errors = []
        self.page.on('pageerror', lambda error: self.errors.append(str(error)))
        self.calls = []
        self.devices = [
            {'deviceId': 'NVR-WAREHOUSE-01', 'deviceIp': '192.168.1.64', 'online': True, 'onlineTime': '2026-09-20 08:30:00'},
            {'deviceId': 'NVR-PACKING-02', 'deviceIp': '192.168.1.65', 'online': True, 'onlineTime': '2026-09-20 09:10:00'},
            {'deviceId': 'NVR-OUTBOUND-03', 'deviceIp': '192.168.1.66', 'online': True, 'onlineTime': '2026-09-20 09:12:00'},
        ]
        self.sessions = []
        self.fail_auth = False
        self.fail_delete = False
        self.fail_hls = False
        self.corrupt_storage = False
        if OFFLINE:
            self.page.expose_binding('__mockApi', lambda _source, request: self.api_response(request))
        self.page.route('**/api/**', self.api_route)
        self.page.route('https://cdn.jsdelivr.net/**', lambda route: route.fulfill(status=200, content_type='text/javascript', body=HLS_STUB))

    def tearDown(self):
        self.context.close()
        self.assertEqual(self.errors, [], f'未捕获的浏览器异常：{self.errors}')

    def api_route(self, route):
        request = route.request
        response = self.api_response({'url': request.url, 'method': request.method,
                                      'headers': request.headers, 'body': request.post_data})
        if 'binary' in response:
            route.fulfill(status=response['status'], content_type='video/mp4',
                          headers=response['headers'], body=response['binary'].encode())
        else:
            route.fulfill(status=response['status'], json=response['json'])

    def api_response(self, request):
        path = request['url'].split('/api', 1)[1]
        method = request['method']
        self.calls.append((method, '/api' + path, request['headers'], request.get('body')))
        if self.fail_auth and path.startswith('/v1/'):
            return {'status': 401, 'json': {'message': 'API Key 错误'}}
        if path == '/health':
            data = {'status': 'UP', 'sdkReady': True, 'onlineDevices': len(self.devices),
                    'activeSessions': sum(s['status'] in ['STARTING', 'RUNNING'] for s in self.sessions),
                    'uptimeSeconds': 32700, 'publicIp': '192.0.2.10', 'cmsPort': 7660, 'previewPort': 8003, 'playbackPort': 8004}
        elif path == '/v1/devices':
            data = self.devices
        elif path == '/v1/sessions':
            data = self.sessions
        elif method == 'POST' and path in ['/v1/preview', '/v1/playback', '/v1/download']:
            body = json.loads(request['body'])
            task_id = f'task-{len(self.sessions) + 1}'
            kind = path.rsplit('/', 1)[1].upper()
            task = {**body, 'sessionId': task_id, 'type': kind, 'status': 'RUNNING',
                    'playUrl': f'/media/{task_id}/index.m3u8', 'createdAt': '2026-09-20 17:30:00'}
            self.sessions.insert(0, task)
            data = {'taskId': task_id} if kind == 'DOWNLOAD' else {'sessionId': task_id, 'playUrl': task['playUrl']}
        elif method == 'DELETE' and path.startswith('/v1/sessions/'):
            if self.fail_delete:
                return {'status': 500, 'json': {'message': '停止失败'}}
            task_id = path.rsplit('/', 1)[1]
            for task in self.sessions:
                if task['sessionId'] == task_id:
                    task['status'] = 'STOPPED'
            data = {'sessionId': task_id, 'status': 'STOPPED'}
        elif method == 'GET' and path.endswith('/file'):
            return {'status': 200, 'binary': 'test-mp4-fixture',
                    'headers': {'Content-Disposition': 'attachment; filename="test-recording.mp4"'}}
        else:
            return {'status': 404, 'json': {'message': '未实现的测试接口'}}
        return {'status': 200, 'json': data}

    def offline_open(self, configured, fragment):
        # 受限环境不进行页面导航或网络访问；使用相同 HTML/CSS/JS，模拟传输与浏览器下载。
        html = re.sub(r'<script\b[^>]*>.*?</script>', '', (STATIC / 'debug.html').read_text(), flags=re.S)
        html = re.sub(r'<link\b[^>]*>', '', html)
        self.page.set_content(html)
        self.page.add_style_tag(path=str(STATIC / 'debug/console.css'))
        stored = json.dumps({'base': self.origin, **({'key': 'test-api-key'} if configured else {})})
        if self.corrupt_storage:
            stored = '{broken json'
        self.page.evaluate("""({stored, stub, failHls}) => {
          function memoryStorage(initial) {
            const data = new Map(Object.entries(initial));
            return { getItem: key => data.get(key) ?? null, setItem: (key, value) => data.set(key, String(value)), removeItem: key => data.delete(key) };
          }
          Object.defineProperty(window, 'localStorage', {configurable: true, value: memoryStorage({'isup-debug': stored})});
          Object.defineProperty(window, 'sessionStorage', {configurable: true, value: memoryStorage({})});
          HTMLMediaElement.prototype.canPlayType = () => '';
          window.fetch = async (url, options = {}) => {
            const signal = options.signal;
            if (signal?.aborted) throw new DOMException('Aborted', 'AbortError');
            const pending = window.__mockApi({url, method: options.method || 'GET', headers: Object.fromEntries(new Headers(options.headers || {})), body: options.body});
            const response = await pending;
            if (signal?.aborted) throw new DOMException('Aborted', 'AbortError');
            return new Response(response.binary ?? JSON.stringify(response.json), {status: response.status, headers: response.headers || {'Content-Type': 'application/json'}});
          };
          const originalAppend = document.head.append.bind(document.head);
          document.head.append = (...nodes) => {
            for (const item of nodes) {
              if (item.tagName === 'SCRIPT' && item.src.includes('cdn.jsdelivr.net')) {
                setTimeout(() => { if (failHls) item.onerror?.(); else { (0, eval)(stub); item.onload?.(); } }, 0);
              } else originalAppend(item);
            }
          };
          window.savedDownloads = [];
          HTMLAnchorElement.prototype.click = function() {
            if (this.download) window.savedDownloads.push({name: this.download, href: this.href});
          };
        }""", {'stored': stored, 'stub': HLS_STUB, 'failHls': self.fail_hls})
        self.page.evaluate('(hash) => { location.hash = hash; }', fragment)
        utils = (STATIC / 'debug/console-utils.js').read_text().replace('export ', '')
        app = re.sub(r'^import \{.*?\} from .*?;\n', '', (STATIC / 'debug/console.js').read_text(), count=1, flags=re.S)
        self.page.add_script_tag(content='(() => {\n' + utils + '\n' + app + '\n})();')

    def open(self, configured=True, fragment='preview'):
        if configured:
            cfg = json.dumps({'base': self.origin, 'key': 'test-api-key'})
            self.context.add_init_script(f"localStorage.setItem('isup-debug', JSON.stringify({cfg}));")
        if OFFLINE:
            self.offline_open(configured, fragment)
        else:
            self.page.goto(f'{self.origin}/debug.html#{fragment}')
        expect(self.page.locator('#stat-service')).to_have_text('运行正常')
        if configured:
            expect(self.page.locator('#device-count')).to_have_text(str(len(self.devices)))

    def click_nav(self, page):
        self.page.locator(f'a[data-page="{page}"]').click()
        expect(self.page.locator('#page-title')).to_have_text({'preview': '实时预览', 'playback': '录像回放', 'download': '录像下载', 'sessions': '会话管理', 'logs': '请求日志'}[page])

    def test_layout_navigation_and_responsive(self):
        self.open()
        for name in ['playback', 'download', 'sessions', 'logs', 'preview']:
            self.click_nav(name)
        for width in [1920, 1440, 1024, 768, 390, 320]:
            self.page.set_viewport_size({'width': width, 'height': 1000})
            for name in ['preview', 'playback', 'download', 'sessions', 'logs']:
                self.page.evaluate('(hash) => { location.hash = hash; }', name)
                expect(self.page.locator('#page-title')).not_to_have_text('')
                self.page.wait_for_timeout(40)
                self.assertLessEqual(self.page.evaluate('document.documentElement.scrollWidth'), width, f'{name} 在 {width}px 下横向溢出')
        self.page.set_viewport_size({'width': 390, 'height': 844})
        self.page.locator('#menu-toggle').click()
        expect(self.page.locator('#menu-toggle')).to_have_attribute('aria-expanded', 'true')
        self.page.locator('[data-page="preview"]').click()
        expect(self.page.locator('#menu-toggle')).to_have_attribute('aria-expanded', 'false')

    def test_unconfigured_and_corrupted_storage(self):
        self.corrupt_storage = True
        self.context.add_init_script("localStorage.setItem('isup-debug', '{broken json');")
        self.open(configured=False)
        self.assertFalse(any('/api/v1/' in call[1] for call in self.calls))
        self.page.locator('#preview-submit').click()
        expect(self.page.locator('#settings-dialog')).to_be_visible()
        expect(self.page.locator('#settings-error')).to_contain_text('API Key')
        if OFFLINE:
            self.page.locator('#base').fill(self.origin)
        self.page.locator('#apikey').fill('new-session-key')
        self.page.locator('#settings-submit').click()
        expect(self.page.locator('#settings-dialog')).not_to_be_visible()
        expect(self.page.locator('#device-count')).to_have_text('3')
        stored = self.page.evaluate("({local: localStorage.getItem('isup-debug'), session: sessionStorage.getItem('isup-debug-session')})")
        self.assertNotIn('new-session-key', stored['local'])
        self.assertIn('new-session-key', stored['session'])

    def test_device_search_and_safe_render(self):
        hostile = "DEV-'<img src=x onerror=alert(1)>"
        self.devices.append({'deviceId': hostile, 'deviceIp': '192.168.1.70', 'online': True})
        self.open()
        self.page.locator('#device-search').fill('192.168.1.70')
        expect(self.page.locator('.device-option')).to_have_count(1)
        self.page.locator('.device-option').click()
        for prefix in ['pv', 'pb', 'dl']:
            expect(self.page.locator(f'#{prefix}-device')).to_have_value(hostile)
        expect(self.page.locator('#devices img')).to_have_count(0)

    def test_validation_prevents_requests(self):
        self.open()
        self.page.locator('#preview-submit').click()
        expect(self.page.locator('#pv-device')).to_have_attribute('aria-invalid', 'true')
        self.page.locator('#pv-device').fill('NVR-WAREHOUSE-01')
        self.page.locator('#pv-channel').fill('1.5')
        self.page.locator('#preview-submit').click()
        expect(self.page.locator('#feedback')).to_contain_text('整数')
        self.click_nav('playback')
        self.page.locator('#pb-device').fill('NVR-WAREHOUSE-01')
        self.page.locator('#pb-start').fill('2026-09-20T16:00')
        self.page.locator('#pb-end').fill('2026-09-20T15:00')
        self.page.locator('#playback-submit').click()
        expect(self.page.locator('#feedback')).to_contain_text('结束时间必须晚于开始时间')
        self.assertEqual([c for c in self.calls if c[0] == 'POST'], [])

    def test_preview_switch_stop_and_payload(self):
        self.open()
        self.page.locator('.device-option').first.click()
        self.page.locator('#pv-stream').select_option('1')
        self.page.locator('#pv-link').select_option('0')
        self.page.locator('#preview-submit').click()
        expect(self.page.locator('#player-status')).to_have_text('进行中')
        expect(self.page.locator('#stop-player')).to_be_enabled()
        self.page.wait_for_function('window.hlsAttached === 1')
        self.click_nav('playback')
        self.page.locator('#pb-start').fill('2026-09-20T14:00')
        self.page.locator('#pb-end').fill('2026-09-20T14:10')
        self.page.locator('#playback-submit').click()
        expect(self.page.locator('#pb-status')).to_have_text('进行中')
        self.page.wait_for_function('window.hlsAttached === 2')
        writes = [c for c in self.calls if c[0] in ['POST', 'DELETE']]
        self.assertEqual([c[:2] for c in writes], [('POST', '/api/v1/preview'), ('DELETE', '/api/v1/sessions/task-1'), ('POST', '/api/v1/playback')])
        self.assertEqual(json.loads(writes[0][3]), {'deviceId': 'NVR-WAREHOUSE-01', 'channel': 1, 'streamType': 1, 'linkMode': 0})
        self.assertEqual(json.loads(writes[2][3])['startTime'], '2026-09-20 14:00:00')
        self.assertEqual(writes[0][2]['x-api-key'], 'test-api-key')
        self.page.locator('#stop-player').click()
        expect(self.page.locator('#stop-player')).to_be_disabled()
        expect(self.page.locator('#player-status')).to_have_text('未播放')
        self.assertGreaterEqual(self.page.evaluate('window.hlsDestroyed'), 2)

    def test_failed_stop_does_not_create_replacement(self):
        self.open()
        self.page.locator('.device-option').first.click()
        self.page.locator('#preview-submit').click()
        expect(self.page.locator('#player-status')).to_have_text('进行中')
        expect(self.page.locator('#preview-submit')).to_be_enabled()
        self.fail_delete = True
        self.page.locator('#preview-submit').click()
        expect(self.page.locator('#feedback')).to_contain_text('停止失败')
        self.assertEqual(len([c for c in self.calls if c[0] == 'POST']), 1)
        expect(self.page.locator('#stop-player')).to_be_enabled()

    def test_download_completion_and_authenticated_save(self):
        self.open()
        self.page.locator('.device-option').first.click()
        self.click_nav('download')
        self.page.locator('#download-submit').click()
        expect(self.page.locator('#dl-status')).to_have_text('进行中')
        expect(self.page.locator('#download-submit')).to_be_disabled()
        expect(self.page.locator('#dl-save')).to_be_disabled()
        self.sessions[0].update(status='COMPLETED', fileSize=12582912)
        self.page.locator('#refresh-all').click()
        expect(self.page.locator('#dl-status')).to_have_text('已完成')
        expect(self.page.locator('#dl-size')).to_have_text('12.00 MB')
        if OFFLINE:
            self.page.locator('#dl-save').click()
            self.page.wait_for_function('window.savedDownloads.length === 1')
            self.assertEqual(self.page.evaluate('window.savedDownloads[0].name'), 'test-recording.mp4')
        else:
            with self.page.expect_download() as event:
                self.page.locator('#dl-save').click()
            self.assertEqual(event.value.suggested_filename, 'test-recording.mp4')
        self.assertEqual([c for c in self.calls if c[1].endswith('/file')][0][2]['x-api-key'], 'test-api-key')

    def test_sessions_filter_pagination_and_stop_confirmation(self):
        self.sessions = [{'sessionId': f'erp-session-{i:03}', 'deviceId': f'DEV-{i:02}', 'channel': 1,
                          'type': 'DOWNLOAD' if i % 2 else 'PREVIEW', 'status': 'RUNNING',
                          'createdAt': '2026-09-20 12:00:00'} for i in range(24)]
        self.open(fragment='sessions')
        expect(self.page.locator('#session-count')).to_have_text('共 24 条')
        expect(self.page.locator('#sessions-body tr')).to_have_count(10)
        self.page.locator('#page-next').click()
        expect(self.page.locator('#page-number')).to_have_text('2 / 3')
        self.page.locator('#session-type').select_option('DOWNLOAD')
        self.page.locator('#session-filter button[type=submit]').click()
        expect(self.page.locator('#session-count')).to_have_text('共 12 条')
        expect(self.page.locator('#page-number')).to_have_text('1 / 2')
        self.page.locator('#session-filter button[type=reset]').click()
        expect(self.page.locator('#session-count')).to_have_text('共 24 条')
        self.page.locator('[data-action="stop-session"]').first.click()
        expect(self.page.locator('#stop-dialog')).to_be_visible()
        self.page.locator('#stop-dialog button[value=cancel]').click()
        self.assertFalse(any(c[0] == 'DELETE' for c in self.calls))
        self.page.locator('[data-action="stop-session"]').first.click()
        self.page.locator('#stop-dialog button[value=confirm]').click()
        expect(self.page.locator('#sessions-body tr').first.locator('.tag')).to_have_text('已停止')
        self.assertEqual(len([c for c in self.calls if c[0] == 'DELETE']), 1)

    def test_unauthorized_is_visible_and_key_not_logged(self):
        self.fail_auth = True
        self.open(configured=False)
        self.page.locator('.topbar [data-action=settings]').click()
        self.page.locator('#apikey').fill('bad-secret-key')
        self.page.locator('#settings-submit').click()
        expect(self.page.locator('#feedback')).to_contain_text('API Key 缺失或不正确')
        self.click_nav('logs')
        self.assertNotIn('bad-secret-key', self.page.locator('#log').inner_text())

    def test_broken_cdn_does_not_block_ui(self):
        self.fail_hls = True
        self.page.unroute('https://cdn.jsdelivr.net/**')
        self.page.route('https://cdn.jsdelivr.net/**', lambda route: route.abort())
        self.open()
        self.page.locator('.device-option').first.click()
        self.page.locator('#preview-submit').click()
        expect(self.page.locator('#player-title')).to_have_text('无法加载视频')
        expect(self.page.locator('#player-hint')).to_contain_text('HLS 播放组件加载失败')
        self.click_nav('sessions')
        expect(self.page.locator('#session-count')).to_have_text('共 1 条')

    def test_connection_switch_clears_old_devices_and_uses_new_key(self):
        self.open()
        self.page.locator('.device-option').first.click()
        self.page.locator('.topbar [data-action=settings]').click()
        self.page.locator('#base').fill(self.origin + '/gateway')
        self.page.locator('#apikey').fill('next-key')
        self.page.locator('#remember-key').uncheck()
        self.page.locator('#settings-submit').click()
        expect(self.page.locator('#settings-dialog')).not_to_be_visible()
        expect(self.page.locator('#pv-device')).to_have_value('')
        expect(self.page.locator('#current-base')).to_have_text(self.origin + '/gateway')
        self.page.wait_for_timeout(100)
        self.assertTrue(any(c[2].get('x-api-key') == 'next-key' for c in self.calls))

    def test_optional_screenshots(self):
        output = os.getenv('SCREENSHOT_DIR')
        if not output:
            self.skipTest('未配置截图输出目录')
        self.open()
        self.page.locator('.device-option').first.click()
        directory = Path(output)
        directory.mkdir(parents=True, exist_ok=True)
        self.page.screenshot(path=str(directory / 'isup-preview-desktop.png'), full_page=True, animations='disabled')
        self.click_nav('playback')
        self.page.screenshot(path=str(directory / 'isup-playback-desktop.png'), full_page=True, animations='disabled')
        self.click_nav('download')
        self.page.screenshot(path=str(directory / 'isup-download-desktop.png'), full_page=True, animations='disabled')
        self.sessions = [{'sessionId': f'erp-session-{i:03}', 'deviceId': self.devices[i % 3]['deviceId'], 'channel': i % 4 + 1,
                          'type': ['PREVIEW', 'PLAYBACK', 'DOWNLOAD'][i % 3],
                          'status': ['RUNNING', 'COMPLETED', 'FAILED', 'STOPPED'][i % 4],
                          'createdAt': f'2026-09-20 17:{30-i:02}:00', 'fileSize': 15728640 if i % 3 == 2 else None} for i in range(16)]
        self.click_nav('sessions')
        expect(self.page.locator('#session-count')).to_have_text('共 16 条')
        self.page.screenshot(path=str(directory / 'isup-sessions-desktop.png'), full_page=True, animations='disabled')
        self.page.set_viewport_size({'width': 390, 'height': 844})
        if OFFLINE:
            self.page.evaluate('location.hash = "preview"')
        else:
            self.page.goto(f'{self.origin}/debug.html#preview')
        expect(self.page.locator('#device-count')).to_have_text('3')
        self.page.screenshot(path=str(directory / 'isup-preview-mobile.png'), full_page=True, animations='disabled')


if __name__ == '__main__':
    unittest.main(verbosity=2)
