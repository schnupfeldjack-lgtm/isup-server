import assert from 'node:assert/strict';
import { afterEach, test } from 'node:test';
import {
  ApiClient, ApiError, downloadFilename, formatBytes, formatLocalDate,
  isActive, normalizeBase, readConfig, resolveMediaUrl, toApiDateTime, writeConfig,
} from '../../src/main/resources/static/debug/console-utils.js';

const originalFetch = globalThis.fetch;
afterEach(() => { globalThis.fetch = originalFetch; });

function memoryStorage(initial = {}) {
  const entries = new Map(Object.entries(initial));
  return {
    getItem: key => entries.get(key) ?? null,
    setItem: (key, value) => entries.set(key, String(value)),
    removeItem: key => entries.delete(key),
  };
}

test('连接地址规范化并拒绝不安全协议、凭据和查询参数', () => {
  assert.equal(normalizeBase(' https://example.com/isup/// '), 'https://example.com/isup');
  for (const value of ['example.com', 'javascript:alert(1)', 'ftp://example.com', 'https://user:pass@example.com', 'https://example.com?key=x', 'https://example.com#page']) {
    assert.throws(() => normalizeBase(value));
  }
});

test('播放地址支持服务上下文路径且拒绝非 HTTP 协议', () => {
  assert.equal(resolveMediaUrl('/media/a/index.m3u8', 'https://example.com/isup'), 'https://example.com/isup/media/a/index.m3u8');
  assert.equal(resolveMediaUrl('https://media.example.com/a.m3u8', 'https://example.com'), 'https://media.example.com/a.m3u8');
  for (const value of ['', null, 'javascript:alert(1)', 'data:text/html,test', 'https://u:p@example.com/a']) {
    assert.throws(() => resolveMediaUrl(value, 'https://example.com'));
  }
});

test('时间保留本地字段并校验闰年和秒数', () => {
  assert.equal(toApiDateTime('2026-09-20T14:10'), '2026-09-20 14:10:00');
  assert.equal(toApiDateTime('2024-02-29T14:10:59'), '2024-02-29 14:10:59');
  assert.equal(formatLocalDate(new Date(2026, 8, 20, 7, 8, 9)), '2026-09-20T07:08:09');
  for (const value of ['', '2026-02-29T12:00', '2026-09-31T12:00', '2026-09-20T24:00', '2026-09-20T12:00:60']) {
    assert.throws(() => toApiDateTime(value));
  }
});

test('状态和字节格式正确处理空值及零值', () => {
  assert.ok(isActive('RUNNING'));
  assert.ok(isActive('STARTING'));
  assert.equal(isActive('COMPLETED'), false);
  assert.equal(formatBytes(null), '—');
  assert.equal(formatBytes(-1), '—');
  assert.equal(formatBytes(0), '0 B');
  assert.equal(formatBytes(1024), '1.0 KB');
  assert.equal(formatBytes(1048576), '1.00 MB');
});

test('下载文件名解析、编码兜底与路径字符过滤', () => {
  assert.equal(downloadFilename('attachment; filename="clip.mp4"', 'fallback.mp4'), 'clip.mp4');
  assert.equal(downloadFilename("attachment; filename*=UTF-8''%E5%BD%95%E5%83%8F.mp4", 'fallback.mp4'), '录像.mp4');
  assert.equal(downloadFilename("filename*=UTF-8''%invalid; filename=clip.mp4", 'fallback.mp4'), 'clip.mp4');
  assert.equal(downloadFilename('filename="../clip.mp4"', 'fallback.mp4'), '.._clip.mp4');
});

test('损坏或禁用存储不影响初始化', () => {
  const local = memoryStorage({ 'isup-debug': '{broken' });
  assert.deepEqual(readConfig(local, null, 'https://example.com'), { base: 'https://example.com', key: '', remember: false });
  assert.deepEqual(readConfig(null, null, 'https://example.com'), { base: 'https://example.com', key: '', remember: false });
});

test('Key 默认会话存储，显式记住后持久化，取消记住清理旧值', () => {
  const local = memoryStorage();
  const session = memoryStorage();
  const config = { base: 'https://example.com', key: 'private-key', remember: false };
  writeConfig(config, local, session);
  assert.ok(!local.getItem('isup-debug').includes(config.key));
  assert.deepEqual(readConfig(local, session, config.base), config);
  writeConfig({ ...config, remember: true }, local, session);
  assert.ok(local.getItem('isup-debug').includes(config.key));
  assert.equal(session.getItem('isup-debug-session'), null);
  writeConfig(config, local, session);
  assert.ok(!local.getItem('isup-debug').includes(config.key));
});

test('请求使用连接快照和请求头鉴权，日志不包含 Key', async () => {
  const config = { base: 'https://example.com/isup', key: 'private-key' };
  const logs = [];
  const client = new ApiClient(config, message => logs.push(message));
  config.base = 'https://other.example.com';
  globalThis.fetch = async (url, options) => {
    assert.equal(url, 'https://example.com/isup/api/v1/preview');
    assert.equal(options.headers['X-API-Key'], 'private-key');
    assert.deepEqual(JSON.parse(options.body), { channel: 1 });
    return new Response('{"sessionId":"a"}');
  };
  assert.deepEqual(await client.request('/api/v1/preview', { method: 'POST', body: { channel: 1 } }), { sessionId: 'a' });
  assert.ok(!logs.join(' ').includes('private-key'));
  assert.equal(client.controllers.size, 0);
});

test('HTTP 错误、非 JSON 响应以及合法 null 响应可区分', async () => {
  const client = new ApiClient({ base: 'https://example.com', key: '' });
  globalThis.fetch = async () => new Response('{"message":"未授权"}', { status: 401 });
  await assert.rejects(client.request('/api/v1/devices'), error => error instanceof ApiError && error.status === 401);
  globalThis.fetch = async () => new Response('<html>proxy</html>');
  await assert.rejects(client.request('/api/health'), /JSON/);
  globalThis.fetch = async () => new Response('null');
  assert.equal(await client.request('/api/health'), null);
});

test('鉴权下载保留二进制与文件名', async () => {
  const client = new ApiClient({ base: 'https://example.com', key: 'private-key' });
  globalThis.fetch = async (_url, options) => {
    assert.equal(options.headers['X-API-Key'], 'private-key');
    return new Response('video-fixture', { headers: { 'Content-Disposition': 'attachment; filename="clip.mp4"' } });
  };
  const result = await client.request('/api/v1/download/a/file', { binary: true });
  assert.equal(await result.blob.text(), 'video-fixture');
  assert.equal(result.disposition, 'attachment; filename="clip.mp4"');
});

test('超时释放请求控制器且不自动重发写请求', async () => {
  const client = new ApiClient({ base: 'https://example.com', key: '' });
  let calls = 0;
  globalThis.fetch = (_url, options) => new Promise((_resolve, reject) => {
    calls++;
    options.signal.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')), { once: true });
  });
  await assert.rejects(client.request('/api/v1/preview', { method: 'POST', timeout: 5 }), /请求超时/);
  assert.equal(calls, 1);
  assert.equal(client.controllers.size, 0);
});

test('切换连接可取消全部在途请求', async () => {
  const client = new ApiClient({ base: 'https://example.com', key: '' });
  globalThis.fetch = (_url, options) => new Promise((_resolve, reject) => {
    options.signal.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')), { once: true });
  });
  const request = client.request('/api/v1/devices');
  const assertion = assert.rejects(request, error => error.name === 'AbortError');
  client.abortAll();
  await assertion;
  assert.equal(client.controllers.size, 0);
});