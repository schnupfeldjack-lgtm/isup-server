export const STATUS_LABELS = Object.freeze({
  STARTING: '启动中', RUNNING: '进行中', COMPLETED: '已完成', FAILED: '失败', STOPPED: '已停止',
});
export const TYPE_LABELS = Object.freeze({ PREVIEW: '实时预览', PLAYBACK: '录像回放', DOWNLOAD: '录像下载' });

export function isActive(status) {
  return status === 'STARTING' || status === 'RUNNING';
}

export function normalizeBase(value) {
  let url;
  try {
    url = new URL(value.trim());
  } catch {
    throw new Error('请输入完整的服务地址，例如 http://127.0.0.1:8080');
  }
  if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.search || url.hash) {
    throw new Error('服务地址仅支持 HTTP / HTTPS，不得包含账号密码、查询参数或锚点');
  }
  return url.href.replace(/\/+$/, '');
}

export function resolveMediaUrl(path, base) {
  if (typeof path !== 'string' || !path.trim()) throw new Error('服务端没有返回播放地址');
  if (/^[a-z][a-z\d+.-]*:/i.test(path.trim()) && !/^https?:\/\//i.test(path.trim())) {
    throw new Error('播放地址必须为有效的 HTTP / HTTPS 地址');
  }
  const url = new URL(/^https?:\/\//i.test(path) ? path : `${base}/${path.replace(/^\/+/, '')}`);
  if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password) {
    throw new Error('播放地址必须为有效的 HTTP / HTTPS 地址');
  }
  return url.href;
}

export function formatLocalDate(date) {
  const pad = value => String(value).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}

/** 不转 UTC，接口需要录像机本地时间。 */
export function toApiDateTime(value) {
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2}))?$/.exec(value);
  if (!match) throw new Error('请选择完整的日期和时间');
  const [year, month, day, hour, minute, second] = match.slice(1).map(part => Number(part || 0));
  const date = new Date(year, month - 1, day, hour, minute, second);
  if (year < 1000 || date.getFullYear() !== year || date.getMonth() !== month - 1 || date.getDate() !== day
      || date.getHours() !== hour || date.getMinutes() !== minute || date.getSeconds() !== second) {
    throw new Error('日期或时间无效');
  }
  return `${match[1]}-${match[2]}-${match[3]} ${match[4]}:${match[5]}:${match[6] || '00'}`;
}

export function formatBytes(value) {
  if (value === null || value === undefined || value === '') return '—';
  const bytes = Number(value);
  if (!Number.isFinite(bytes) || bytes < 0) return '—';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1048576) return `${(bytes / 1024).toFixed(1)} KB`;
  if (bytes < 1073741824) return `${(bytes / 1048576).toFixed(2)} MB`;
  return `${(bytes / 1073741824).toFixed(2)} GB`;
}

export function downloadFilename(header, fallback) {
  const encoded = /filename\*=UTF-8''([^;]+)/i.exec(header || '');
  const plain = /filename="([^"]+)"|filename=([^;]+)/i.exec(header || '');
  let name = plain ? (plain[1] || plain[2]).trim() : fallback;
  if (encoded) {
    try { name = decodeURIComponent(encoded[1]); } catch { /* 使用普通文件名兜底。 */ }
  }
  return name.replace(/[\\/\u0000-\u001f\u007f]/g, '_').slice(0, 180) || fallback;
}

export function readConfig(local, session, origin) {
  const read = (storage, key) => {
    try {
      const value = JSON.parse(storage.getItem(key) || '{}');
      return value && typeof value === 'object' && !Array.isArray(value) ? value : {};
    } catch { return {}; }
  };
  const persisted = read(local, 'isup-debug');
  const temporary = read(session, 'isup-debug-session');
  let base;
  try { base = normalizeBase(persisted.base || origin); } catch { base = origin; }
  const remembered = typeof persisted.key === 'string' && Boolean(persisted.key);
  const key = remembered ? persisted.key : (temporary.base === base && typeof temporary.key === 'string' ? temporary.key : '');
  return { base, key, remember: remembered };
}

export function writeConfig(config, local, session) {
  local.setItem('isup-debug', JSON.stringify({ base: config.base, ...(config.remember ? { key: config.key } : {}) }));
  if (config.remember) session.removeItem('isup-debug-session');
  else session.setItem('isup-debug-session', JSON.stringify({ base: config.base, key: config.key }));
}

export class ApiError extends Error {
  constructor(message, status = 0) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
  }
}

/** 请求使用连接快照；切换服务时可统一取消，禁止把旧请求重发到新服务。 */
export class ApiClient {
  constructor(config, onLog = () => {}) {
    this.config = { ...config };
    this.onLog = onLog;
    this.controllers = new Set();
  }

  abortAll() {
    this.controllers.forEach(controller => controller.abort());
    this.controllers.clear();
  }

  async request(path, { method = 'GET', body, quiet = false, binary = false, timeout = 20000 } = {}) {
    const controller = new AbortController();
    this.controllers.add(controller);
    let timedOut = false;
    const timer = setTimeout(() => { timedOut = true; controller.abort(); }, timeout);
    const headers = {};
    if (this.config.key) headers['X-API-Key'] = this.config.key;
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    if (!quiet) this.onLog(`${method} ${path}`, 'request');
    try {
      const response = await fetch(`${this.config.base}${path}`, {
        method, headers, signal: controller.signal, cache: 'no-store',
        body: body === undefined ? undefined : JSON.stringify(body),
      });
      if (response.ok && binary) {
        const blob = await response.blob();
        if (!quiet) this.onLog(`${response.status} 文件接收完成（${formatBytes(blob.size)}）`, 'success');
        return { blob, disposition: response.headers.get('Content-Disposition') };
      }
      const text = await response.text();
      let data;
      let validJson = true;
      try { data = text ? JSON.parse(text) : null; } catch { validJson = false; }
      if (!response.ok) {
        const message = typeof data?.message === 'string' ? data.message : `请求失败（HTTP ${response.status}），请检查服务端日志`;
        throw new ApiError(message, response.status);
      }
      if (!validJson) throw new ApiError('接口未返回有效 JSON，请检查服务地址和反向代理配置');
      if (!quiet) this.onLog(`${response.status} ${method} ${path}`, 'success');
      return data;
    } catch (error) {
      if (timedOut) throw new ApiError('请求超时，请检查服务端状态；创建任务请先查看会话列表，避免重复提交');
      if (error.name === 'AbortError' || error instanceof ApiError) throw error;
      throw new ApiError('网络请求失败，请检查服务地址、跨域配置以及 HTTPS 混合内容限制');
    } finally {
      clearTimeout(timer);
      this.controllers.delete(controller);
    }
  }
}