import {
  ApiClient, STATUS_LABELS, TYPE_LABELS, downloadFilename, formatBytes,
  formatLocalDate, isActive, normalizeBase, readConfig, resolveMediaUrl, toApiDateTime, writeConfig,
} from './console-utils.js';

const $ = id => document.getElementById(id);
const PAGES = Object.freeze({
  preview: ['实时预览', '选择在线设备，发起视频预览并查看推流状态。'],
  playback: ['录像回放', '按设备本地时间查询录像，与实时预览共用播放器。'],
  download: ['录像下载', '按时间段生成 MP4 文件，任务完成后下载到本地。'],
  sessions: ['会话管理', '统一查看预览、回放和下载任务，包括 ERP 发起的会话。'],
  logs: ['请求日志', '查看本页接口调用与错误信息，快速定位联调问题。'],
});
const LOG_LABELS = Object.freeze({ info: '提示', request: '请求', success: '成功', error: '错误' });
const POLL_INTERVAL = 3000;
const LOG_LIMIT = 300;
const storage = name => { try { return window[name]; } catch { return null; } };
const config = readConfig(storage('localStorage'), storage('sessionStorage'), location.origin);
const state = {
  config, client: new ApiClient(config, log), version: 0, page: 'preview',
  devices: [], devicesLoaded: false, devicesError: '', selectedDevice: '',
  sessions: [], sessionsLoaded: false, sessionsError: '', sessionPage: 1,
  filter: { search: '', type: '', status: '' }, logs: [],
  player: null, playerToken: 0, hls: null, download: null,
  playBusy: false, downloadBusy: false, mutations: 0, pollTimer: null, pollFailures: 0, authFailed: false,
  inflight: new Map(), saving: new Set(),
};
let hlsLoader;

function node(tag, className, text) {
  const element = document.createElement(tag);
  if (className) element.className = className;
  if (text !== undefined) element.textContent = String(text);
  return element;
}

function redact(value) {
  const text = String(value ?? '');
  return state.config.key ? text.split(state.config.key).join('[已隐藏]') : text;
}

function log(message, level = 'info') {
  state.logs.push({ time: new Date().toLocaleTimeString('zh-CN', { hour12: false }), level, message: redact(message) });
  if (state.logs.length > LOG_LIMIT) state.logs.splice(0, state.logs.length - LOG_LIMIT);
  $('log-count').textContent = state.logs.length;
  if (state.page === 'logs') renderLogs();
}

function notify(message, level = 'error') {
  $('feedback-text').textContent = redact(message);
  $('feedback').dataset.level = level;
  $('feedback').hidden = false;
}

function report(error) {
  if (error.name === 'AbortError') return;
  if (error.status === 401) {
    state.authFailed = true;
    notify('API Key 缺失或不正确，请打开「连接配置」核对 isup.api-key。');
  } else notify(error.message || '操作失败，请查看请求日志');
  log(error.message || '操作失败', 'error');
}

function renderLogs() {
  const box = $('log');
  const level = $('log-level').value;
  const follow = box.scrollHeight - box.scrollTop - box.clientHeight < 40;
  const entries = state.logs.filter(entry => !level || entry.level === level);
  const fragment = document.createDocumentFragment();
  for (const entry of entries) {
    const line = node('div', 'log-entry');
    line.dataset.level = entry.level;
    line.append(node('span', 'log-time', entry.time), node('span', 'log-level', LOG_LABELS[entry.level]), node('span', 'log-message', entry.message));
    fragment.append(line);
  }
  if (!entries.length) fragment.append(node('p', 'empty-state', '暂无符合条件的日志'));
  box.replaceChildren(fragment);
  if (follow) box.scrollTop = box.scrollHeight;
}

function setStatus(element, status, fallback = '—') {
  element.className = 'tag';
  element.dataset.status = Object.hasOwn(STATUS_LABELS, status) ? status : '';
  element.textContent = STATUS_LABELS[status] || fallback;
}

function closeMenu() {
  document.body.classList.remove('sidebar-open');
  document.querySelector('.sidebar-mask').hidden = true;
  $('menu-toggle').setAttribute('aria-expanded', 'false');
}

function navigate() {
  const page = location.hash.slice(1);
  state.page = Object.hasOwn(PAGES, page) ? page : 'preview';
  const [title, description] = PAGES[state.page];
  $('page-title').textContent = title;
  $('page-description').textContent = description;
  $('breadcrumb-page').textContent = title;
  document.title = `${title} · ISUP 视频管理`;
  document.querySelectorAll('[data-page]').forEach(link => {
    if (link.dataset.page === state.page) link.setAttribute('aria-current', 'page');
    else link.removeAttribute('aria-current');
  });
  const monitor = state.page === 'preview' || state.page === 'playback';
  $('view-monitor').hidden = !monitor;
  ['download', 'sessions', 'logs'].forEach(name => { $(`view-${name}`).hidden = state.page !== name; });
  $('preview-form').hidden = state.page !== 'preview';
  $('playback-form').hidden = state.page !== 'playback';
  $('monitor-title').textContent = state.page === 'playback' ? '回放配置' : '预览配置';
  if (!state.player) showPlaceholder('等待播放', `选择设备并点击「${state.page === 'playback' ? '开始回放' : '开始预览'}」`);
  if (state.page === 'logs') renderLogs();
  if (state.page === 'sessions' && state.config.key && !state.authFailed) void refreshSessions();
  else schedulePolling();
  closeMenu();
}

function openSettings() {
  $('base').value = state.config.base;
  $('apikey').value = state.config.key;
  $('remember-key').checked = state.config.remember;
  $('settings-error').hidden = true;
  $('apikey').type = 'password';
  $('toggle-key').textContent = '显示';
  $('toggle-key').setAttribute('aria-label', '显示 API Key');
  $('toggle-key').setAttribute('aria-pressed', 'false');
  if (!$('settings-dialog').open) $('settings-dialog').showModal();
}

function requireKey() {
  if (state.config.key) return true;
  openSettings();
  $('settings-error').textContent = '请先填写 API Key，再执行设备或视频操作。';
  $('settings-error').hidden = false;
  $('apikey').focus();
  return false;
}

async function saveSettings(event) {
  event.preventDefault();
  try {
    if (state.mutations || state.saving.size) throw new Error('有操作正在执行，请在操作结束后切换连接，避免任务状态丢失。');
    const next = { base: normalizeBase($('base').value), key: $('apikey').value.trim(), remember: $('remember-key').checked };
    const changed = next.base !== state.config.base || next.key !== state.config.key;
    const hadTasks = Boolean(state.player || (state.download && isActive(state.download.status)));
    if (changed) {
      state.version++;
      state.client.abortAll();
      clearTimeout(state.pollTimer);
      state.inflight.clear();
      resetPlayer();
      state.download = null;
      state.devices = [];
      state.devicesLoaded = false;
      state.devicesError = '';
      state.selectedDevice = '';
      state.sessions = [];
      state.sessionsLoaded = false;
      state.sessionsError = '';
      state.pollFailures = 0;
      state.sessionPage = 1;
      $('refresh-devices').disabled = false;
      $('refresh-sessions').disabled = false;
      ['pv', 'pb', 'dl'].forEach(prefix => { $(`${prefix}-device`).value = ''; });
    }
    state.config = next;
    state.authFailed = false;
    if (changed) state.client = new ApiClient(next, log);
    $('current-base').textContent = next.base;
    try { writeConfig(next, storage('localStorage'), storage('sessionStorage')); }
    catch { log('浏览器存储不可用，连接配置仅在本次页面打开期间有效。', 'error'); }
    $('settings-dialog').close();
    $('feedback').hidden = true;
    if (changed && hadTasks) notify('已切换连接。原服务的会话不会自动停止，请在原服务的会话管理中处理。', 'info');
    renderDevices();
    renderSessions();
    renderDownload();
    await refreshAll();
  } catch (error) {
    $('settings-error').textContent = error.message;
    $('settings-error').hidden = false;
  }
}

/** 同类刷新只允许一个在途请求；版本号隔离切换连接前的迟到响应。 */
function singleFlight(name, operation) {
  if (state.inflight.has(name)) return state.inflight.get(name);
  const promise = operation().finally(() => {
    if (state.inflight.get(name) === promise) state.inflight.delete(name);
  });
  state.inflight.set(name, promise);
  return promise;
}

function refreshHealth() {
  return singleFlight('health', async () => {
    const version = state.version;
    try {
      const health = await state.client.request('/api/health');
      if (version !== state.version) return;
      if (!health || typeof health.status !== 'string') throw new Error('健康检查响应格式不正确');
      const up = health.status === 'UP';
      $('stat-service').textContent = up ? '运行正常' : '服务异常';
      $('stat-service').className = `text-status ${up ? 'tone-success' : 'tone-error'}`;
      $('stat-sdk').textContent = health.sdkReady ? '已就绪' : '未就绪';
      $('stat-sdk').className = `text-status ${health.sdkReady ? 'tone-success' : 'tone-warning'}`;
      $('stat-devices').textContent = health.onlineDevices ?? '—';
      $('stat-sessions').textContent = health.activeSessions ?? '—';
      const minutes = Math.floor(Math.max(0, Number(health.uptimeSeconds) || 0) / 60);
      $('stat-uptime').textContent = `已运行 ${Math.floor(minutes / 60)} 小时 ${minutes % 60} 分钟`;
      $('stat-ports').textContent = `${health.cmsPort ?? '—'} / ${health.previewPort ?? '—'} / ${health.playbackPort ?? '—'}`;
      $('stat-ports').title = `服务公网地址：${health.publicIp || '未配置'}`;
      $('connection-state').textContent = up ? '服务已连接' : '服务异常';
      $('connection-state').className = `connection-state ${up ? 'tone-success' : 'tone-error'}`;
    } catch (error) {
      if (version !== state.version || error.name === 'AbortError') return;
      $('stat-service').textContent = '连接失败';
      $('stat-service').className = 'text-status tone-error';
      $('stat-uptime').textContent = '请检查连接配置';
      $('stat-sdk').textContent = '—';
      $('stat-sdk').className = 'text-status';
      $('stat-devices').textContent = '—';
      $('stat-sessions').textContent = '—';
      $('stat-ports').textContent = '注册 / 预览 / 回放端口';
      $('connection-state').textContent = '连接失败';
      $('connection-state').className = 'connection-state tone-error';
      report(error);
    }
  });
}

function refreshDevices() {
  if (!state.config.key) return Promise.resolve();
  return singleFlight('devices', async () => {
    const version = state.version;
    $('refresh-devices').disabled = true;
    try {
      const devices = await state.client.request('/api/v1/devices');
      if (version !== state.version) return;
      if (!Array.isArray(devices)) throw new Error('设备列表响应格式不正确');
      state.devices = devices.filter(device => device && typeof device.deviceId === 'string');
      state.devicesLoaded = true;
      state.devicesError = '';
      $('stat-devices').textContent = state.devices.filter(device => device.online).length;
    } catch (error) {
      if (version !== state.version || error.name === 'AbortError') return;
      state.devicesError = '设备加载失败，请检查连接配置后重试';
      report(error);
    } finally {
      if (version === state.version) { $('refresh-devices').disabled = false; renderDevices(); }
    }
  });
}

function renderDevices() {
  const keyword = $('device-search').value.trim().toLowerCase();
  const list = state.devices.filter(device => `${device.deviceId} ${device.deviceIp || ''}`.toLowerCase().includes(keyword));
  const fragment = document.createDocumentFragment();
  if (state.devicesError) fragment.append(node('p', 'field-error', `${state.devicesError}${state.devices.length ? '（以下为上次结果）' : ''}`));
  for (const device of list) {
    const button = node('button', 'device-option');
    button.type = 'button';
    button.dataset.device = device.deviceId;
    button.setAttribute('aria-pressed', String(device.deviceId === state.selectedDevice));
    button.title = `设备：${device.deviceId}\n状态：${device.online ? '在线' : '离线'}\n上线时间：${device.onlineTime || '—'}`;
    const icon = node('span', 'device-icon');
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    const use = document.createElementNS('http://www.w3.org/2000/svg', 'use');
    use.setAttribute('href', '#icon-video');
    svg.append(use);
    icon.append(svg);
    const copy = node('span', 'device-copy');
    copy.append(node('strong', '', device.deviceId), node('small', '', device.deviceIp || 'IP 未上报'));
    const dot = node('span', `device-online${device.online ? '' : ' offline'}`);
    dot.setAttribute('aria-label', device.online ? '在线' : '离线');
    button.append(icon, copy, dot);
    fragment.append(button);
  }
  if (!list.length && !state.devicesError) fragment.append(node('p', 'empty-state', !state.devicesLoaded ? '配置 API Key 后加载设备' : keyword ? '没有匹配的设备' : '暂无在线设备，请检查注册端口、ehome-key 和设备 ISUP 配置'));
  $('devices').replaceChildren(fragment);
  $('device-count').textContent = state.devices.length;
  $('device-options').replaceChildren(...state.devices.map(device => {
    const option = node('option');
    option.value = device.deviceId;
    option.label = device.deviceIp || device.deviceId;
    return option;
  }));
}

function selectDevice(deviceId) {
  state.selectedDevice = deviceId;
  ['pv', 'pb', 'dl'].forEach(prefix => {
    $(`${prefix}-device`).value = deviceId;
    $(`${prefix}-device`).removeAttribute('aria-invalid');
  });
  renderDevices();
  log(`已选择设备 ${deviceId}`);
}

function invalid(id, message) {
  $(id).setAttribute('aria-invalid', 'true');
  $(id).focus();
  throw new Error(message);
}

function readParameters(prefix) {
  const deviceId = $(`${prefix}-device`).value.trim();
  const channel = Number($(`${prefix}-channel`).value);
  if (!deviceId) invalid(`${prefix}-device`, '请选择或输入设备编号');
  if (!Number.isInteger(channel) || channel < 1 || channel > 2147483647) invalid(`${prefix}-channel`, '通道号必须是大于等于 1 的整数');
  const body = { deviceId, channel };
  if (prefix === 'pv') {
    body.streamType = Number($('pv-stream').value);
    if ($('pv-link').value !== '') body.linkMode = Number($('pv-link').value);
  } else {
    for (const name of ['start', 'end']) {
      try { body[`${name}Time`] = toApiDateTime($(`${prefix}-${name}`).value); }
      catch (error) { invalid(`${prefix}-${name}`, `${name === 'start' ? '开始' : '结束'}时间：${error.message}`); }
    }
    if (body.endTime <= body.startTime) invalid(`${prefix}-end`, '结束时间必须晚于开始时间');
  }
  return body;
}

function fillRange(prefix, minutes) {
  const end = new Date();
  $(`${prefix}-start`).value = formatLocalDate(new Date(end.getTime() - minutes * 60000));
  $(`${prefix}-end`).value = formatLocalDate(end);
  ['start', 'end'].forEach(name => $(`${prefix}-${name}`).removeAttribute('aria-invalid'));
}

function showPlaceholder(title, hint) {
  $('player-title').textContent = title;
  $('player-hint').textContent = hint;
  $('player-placeholder').hidden = false;
}

function clearMedia() {
  state.playerToken++;
  if (state.hls) { state.hls.destroy(); state.hls = null; }
  const video = $('video');
  video.pause();
  video.removeAttribute('src');
  video.load();
}

function resetPlayer() {
  clearMedia();
  state.player = null;
  $('play-meta').textContent = '尚未创建播放会话';
  $('play-meta').title = '';
  setStatus($('player-status'), '', '未播放');
  $('pv-status').textContent = '';
  $('pb-status').textContent = '';
  showPlaceholder('等待播放', '选择设备并开始预览或回放');
  updatePlayerButtons();
}

function updatePlayerButtons() {
  $('preview-submit').disabled = state.playBusy;
  $('playback-submit').disabled = state.playBusy;
  $('stop-player').disabled = state.playBusy || !state.player;
  $('reload-player').disabled = state.playBusy || !state.player || !['RUNNING', 'COMPLETED'].includes(state.player.status);
}

async function startMedia(type) {
  if (state.playBusy || !requireKey()) return;
  const prefix = type === 'PREVIEW' ? 'pv' : 'pb';
  let body;
  try { body = readParameters(prefix); } catch (error) { report(error); return; }
  state.playBusy = true;
  state.mutations++;
  updatePlayerButtons();
  const version = state.version;
  try {
    if (state.player) {
      await state.client.request(`/api/v1/sessions/${encodeURIComponent(state.player.sessionId)}`, { method: 'DELETE' });
      resetPlayer();
    }
    $(`${prefix}-status`).textContent = '正在创建会话…';
    const response = await state.client.request(type === 'PREVIEW' ? '/api/v1/preview' : '/api/v1/playback', { method: 'POST', body });
    if (version !== state.version) return;
    if (!response || typeof response.sessionId !== 'string') throw new Error('接口未返回有效的会话编号，请刷新会话列表核对');
    state.player = { ...body, sessionId: response.sessionId, playUrl: response.playUrl, type, status: 'STARTING', waitingAt: Date.now(), attached: false };
    setStatus($(`${prefix}-status`), 'STARTING');
    setStatus($('player-status'), 'STARTING');
    $('play-meta').textContent = `${body.deviceId} · 通道 ${body.channel}`;
    showPlaceholder('等待设备推流', '会话已创建，正在等待 HLS 视频流就绪。');
    await refreshSessions();
  } catch (error) {
    $(`${prefix}-status`).textContent = redact(error.message);
    report(error);
  } finally {
    state.playBusy = false;
    state.mutations--;
    updatePlayerButtons();
    schedulePolling();
  }
}

function loadHls() {
  if (window.Hls) return Promise.resolve(window.Hls);
  if (hlsLoader) return hlsLoader;
  hlsLoader = new Promise((resolve, reject) => {
    const script = document.createElement('script');
    const timer = setTimeout(() => fail(), 15000);
    function fail() {
      clearTimeout(timer);
      script.remove();
      hlsLoader = null;
      reject(new Error('HLS 播放组件加载失败，请检查 CDN 网络后点击「重载流」'));
    }
    script.src = 'https://cdn.jsdelivr.net/npm/hls.js@1.5.17/dist/hls.min.js';
    script.async = true;
    script.onload = () => {
      clearTimeout(timer);
      if (window.Hls) resolve(window.Hls);
      else fail();
    };
    script.onerror = fail;
    document.head.append(script);
  });
  return hlsLoader;
}

async function attachPlayer() {
  const session = state.player;
  if (!session || !['RUNNING', 'COMPLETED'].includes(session.status)) return;
  clearMedia();
  const token = state.playerToken;
  session.attached = true;
  const video = $('video');
  const stillCurrent = () => token === state.playerToken && state.player === session;
  const startVideo = () => {
    if (!stillCurrent()) return;
    $('player-placeholder').hidden = true;
    video.play().catch(() => { if (stillCurrent()) log('浏览器未自动播放，请点击播放器的播放按钮。'); });
  };
  try {
    const url = resolveMediaUrl(session.playUrl, state.config.base);
    $('play-meta').textContent = `${session.deviceId} · 通道 ${session.channel}`;
    $('play-meta').title = redact(url);
    log(`加载视频流：${url}`);
    if (video.canPlayType('application/vnd.apple.mpegurl')) {
      video.src = url;
      startVideo();
    } else {
      const Hls = await loadHls();
      if (!stillCurrent()) return;
      if (!Hls.isSupported()) throw new Error('当前浏览器不支持 HLS 播放，请使用支持 MediaSource 的浏览器');
      const hls = new Hls({ manifestLoadPolicy: { default: {
        maxTimeToFirstByteMs: 30000, maxLoadTimeMs: 60000,
        timeoutRetry: { maxNumRetry: 6, retryDelayMs: 1000, maxRetryDelayMs: 8000 },
        errorRetry: { maxNumRetry: 8, retryDelayMs: 1500, maxRetryDelayMs: 8000 },
      } } });
      state.hls = hls;
      hls.on(Hls.Events.MANIFEST_PARSED, startVideo);
      hls.on(Hls.Events.ERROR, (_event, data) => {
        if (stillCurrent() && data?.fatal) {
          showPlaceholder('视频流加载失败', '可尝试重载流；仍失败时请检查设备推流和服务端日志。');
          log(`HLS 错误：${data.type} / ${data.details}`, 'error');
        }
      });
      hls.loadSource(url);
      hls.attachMedia(video);
    }
  } catch (error) {
    if (!stillCurrent()) return;
    showPlaceholder('无法加载视频', error.message);
    report(error);
  }
}

async function stopCurrentPlayer() {
  if (!state.player || state.playBusy) return;
  state.playBusy = true;
  state.mutations++;
  updatePlayerButtons();
  try {
    await state.client.request(`/api/v1/sessions/${encodeURIComponent(state.player.sessionId)}`, { method: 'DELETE' });
    resetPlayer();
    await refreshSessions();
  } catch (error) { report(error); }
  finally { state.playBusy = false; state.mutations--; updatePlayerButtons(); }
}

async function startDownload() {
  if (state.downloadBusy || $('download-submit').disabled || !requireKey()) return;
  let body;
  try { body = readParameters('dl'); } catch (error) { report(error); return; }
  state.downloadBusy = true;
  $('download-submit').disabled = true;
  state.mutations++;
  try {
    const response = await state.client.request('/api/v1/download', { method: 'POST', body });
    if (!response || typeof response.taskId !== 'string') throw new Error('接口未返回有效任务编号，请刷新会话列表核对');
    state.download = { ...body, sessionId: response.taskId, type: 'DOWNLOAD', status: 'STARTING' };
    renderDownload();
    await refreshSessions();
  } catch (error) { report(error); }
  finally {
    state.downloadBusy = false;
    state.mutations--;
    $('download-submit').disabled = Boolean(state.download && isActive(state.download.status));
    schedulePolling();
  }
}

function renderDownload() {
  const task = state.download;
  setStatus($('dl-status'), task?.status, '未创建');
  $('dl-id').textContent = task?.sessionId || '—';
  $('dl-device-meta').textContent = task ? `${task.deviceId} / 通道 ${task.channel}` : '—';
  $('dl-size').textContent = task ? formatBytes(task.fileSize ?? task.bytesReceived) : '—';
  $('dl-range').textContent = task ? `${task.startTime || '—'} 至 ${task.endTime || '—'}` : '—';
  $('dl-title').textContent = task ? `任务${STATUS_LABELS[task.status] || '状态未知'}` : '暂无下载任务';
  const hints = { STARTING: '等待设备推送录像数据，请勿重复提交。', RUNNING: '服务端正在接收并生成录像文件，请在完成后保存。', COMPLETED: 'MP4 已生成，可以保存到本地。', FAILED: '任务失败，请检查服务端日志。', STOPPED: '任务已停止，可重新创建下载任务。' };
  $('dl-hint').textContent = task ? redact(task.error || hints[task.status] || '请在会话管理中查看任务状态。') : '填写左侧参数，创建录像下载任务。';
  $('dl-save').disabled = !task || task.status !== 'COMPLETED' || state.saving.has(task.sessionId);
  $('download-submit').disabled = state.downloadBusy || Boolean(task && isActive(task.status));
}

async function saveFile(sessionId) {
  if (!sessionId || state.saving.has(sessionId) || !requireKey()) return;
  state.saving.add(sessionId);
  renderDownload();
  renderSessions();
  try {
    const { blob, disposition } = await state.client.request(`/api/v1/download/${encodeURIComponent(sessionId)}/file`, { binary: true, timeout: 120000 });
    const url = URL.createObjectURL(blob);
    const anchor = node('a');
    anchor.href = url;
    anchor.download = downloadFilename(disposition, `${sessionId}.mp4`);
    document.body.append(anchor);
    anchor.click();
    anchor.remove();
    setTimeout(() => URL.revokeObjectURL(url), 10000);
    notify(`文件已交给浏览器下载：${anchor.download}`, 'success');
  } catch (error) { report(error); }
  finally { state.saving.delete(sessionId); renderDownload(); renderSessions(); }
}

function shouldPoll() {
  return !document.hidden && Boolean(state.config.key) && !state.authFailed
    && ((state.page === 'sessions' && $('auto-refresh').checked)
      || Boolean(state.player && isActive(state.player.status))
      || Boolean(state.download && isActive(state.download.status)));
}

function schedulePolling() {
  clearTimeout(state.pollTimer);
  state.pollTimer = null;
  if (shouldPoll()) state.pollTimer = setTimeout(() => void refreshSessions(true), Math.min(POLL_INTERVAL * 2 ** state.pollFailures, 30000));
}

function refreshSessions(quiet = false) {
  if (!state.config.key) return Promise.resolve();
  return singleFlight('sessions', async () => {
    const version = state.version;
    clearTimeout(state.pollTimer);
    $('refresh-sessions').disabled = true;
    try {
      const sessions = await state.client.request('/api/v1/sessions', { quiet });
      if (version !== state.version) return;
      if (!Array.isArray(sessions)) throw new Error('会话列表响应格式不正确');
      state.sessions = sessions.filter(session => session && typeof session.sessionId === 'string');
      state.sessionsLoaded = true;
      state.sessionsError = '';
      state.pollFailures = 0;
      $('stat-sessions').textContent = state.sessions.filter(session => isActive(session.status)).length;
      updateTrackedSessions();
      renderSessions();
    } catch (error) {
      if (version !== state.version || error.name === 'AbortError') return;
      state.sessionsError = '刷新失败，以下为上次结果；请检查连接后重试。';
      if (!quiet || state.pollFailures === 0 || error.status === 401) report(error);
      state.pollFailures = Math.min(state.pollFailures + 1, 4);
      renderSessions();
    } finally {
      if (version === state.version) { $('refresh-sessions').disabled = false; schedulePolling(); }
    }
  });
}

function updateTrackedSessions() {
  const player = state.player;
  if (player) {
    const latest = state.sessions.find(session => session.sessionId === player.sessionId);
    if (latest) {
      Object.assign(player, latest);
      player.confirmed = true;
      const prefix = player.type === 'PREVIEW' ? 'pv' : 'pb';
      setStatus($(`${prefix}-status`), player.status);
      setStatus($('player-status'), player.status);
      if (['RUNNING', 'COMPLETED'].includes(player.status) && !player.attached) void attachPlayer();
      if (player.status === 'FAILED' || player.status === 'STOPPED') {
        const status = player.status;
        const error = player.error;
        resetPlayer();
        setStatus($('player-status'), status);
        setStatus($(`${prefix}-status`), status);
        showPlaceholder(status === 'FAILED' ? '播放会话失败' : '播放已停止', redact(error || '可重新创建预览或回放会话。'));
      } else if (player.status === 'STARTING' && Date.now() - player.waitingAt > 60000) {
        showPlaceholder('设备推流等待时间较长', '请检查预览 / 回放端口和设备网络；可停止后重新发起。');
      }
    } else if (player.confirmed) {
      resetPlayer();
      showPlaceholder('会话已失效', '服务端会话已清理或服务已重启，请重新发起播放。');
    }
    updatePlayerButtons();
  }
  if (state.download) {
    const latest = state.sessions.find(session => session.sessionId === state.download.sessionId);
    if (latest) Object.assign(state.download, latest, { confirmed: true });
    else if (state.download.confirmed) Object.assign(state.download, { status: 'FAILED', error: '任务已被服务端清理或服务已重启，请重新创建。' });
    renderDownload();
  }
}

function renderSessions() {
  const focusId = document.activeElement?.dataset.session;
  const { search, type, status } = state.filter;
  const list = state.sessions.filter(session => (!search || `${session.sessionId} ${session.deviceId || ''}`.toLowerCase().includes(search)) && (!type || session.type === type) && (!status || session.status === status));
  const size = Number($('page-size').value);
  const pages = Math.max(1, Math.ceil(list.length / size));
  state.sessionPage = Math.min(Math.max(1, state.sessionPage), pages);
  const fragment = document.createDocumentFragment();
  if (state.sessionsError) {
    const row = node('tr');
    const cell = node('td', 'field-error', state.sessionsError);
    cell.colSpan = 7;
    row.append(cell);
    fragment.append(row);
  }
  for (const session of list.slice((state.sessionPage - 1) * size, state.sessionPage * size)) {
    const row = node('tr');
    const idCell = node('td');
    const id = node('span', 'mono cell-main', session.sessionId);
    id.title = session.sessionId;
    idCell.append(id);
    const deviceCell = node('td');
    const device = node('span', 'cell-main', session.deviceId || '—');
    device.title = session.deviceId || '';
    deviceCell.append(device, node('span', 'cell-sub', `通道 ${session.channel ?? '—'}`));
    const statusCell = node('td');
    const badge = node('span');
    setStatus(badge, session.status, '未知状态');
    statusCell.append(badge);
    if (session.error) {
      const error = node('span', 'cell-error', redact(session.error));
      error.title = redact(session.error);
      statusCell.append(error);
    }
    const action = node('td', 'actions-column');
    if (isActive(session.status) || (session.type === 'DOWNLOAD' && session.status === 'COMPLETED')) {
      const downloading = session.type === 'DOWNLOAD' && session.status === 'COMPLETED';
      const button = node('button', `table-action${downloading ? '' : ' danger'}`, downloading ? '保存 MP4' : '停止');
      button.type = 'button';
      button.dataset.session = session.sessionId;
      button.dataset.action = downloading ? 'save-session' : 'stop-session';
      button.disabled = state.saving.has(session.sessionId) || (state.playBusy && session.sessionId === state.player?.sessionId);
      action.append(button);
    } else action.textContent = '—';
    row.append(idCell, node('td', '', TYPE_LABELS[session.type] || '未知类型'), deviceCell, statusCell, node('td', 'mono', formatBytes(session.fileSize)), node('td', 'mono', session.createdAt || '—'), action);
    fragment.append(row);
  }
  if (!list.length && !state.sessionsError) {
    const row = node('tr');
    const cell = node('td', 'empty-cell', !state.sessionsLoaded ? '配置 API Key 后加载会话' : state.sessions.length ? '没有符合筛选条件的会话' : '暂无会话，可先创建预览、回放或下载任务');
    cell.colSpan = 7;
    row.append(cell);
    fragment.append(row);
  }
  $('sessions-body').replaceChildren(fragment);
  $('session-count').textContent = `共 ${list.length} 条`;
  $('page-number').textContent = `${state.sessionPage} / ${pages}`;
  $('page-prev').disabled = state.sessionPage <= 1;
  $('page-next').disabled = state.sessionPage >= pages;
  if (focusId) Array.from($('sessions-body').querySelectorAll('button')).find(button => button.dataset.session === focusId)?.focus({ preventScroll: true });
}

async function confirmStop(sessionId) {
  const version = state.version;
  const dialog = $('stop-dialog');
  if (dialog.open) return;
  $('stop-target').textContent = sessionId;
  dialog.returnValue = 'cancel';
  dialog.showModal();
  const confirmed = await new Promise(resolve => dialog.addEventListener('close', () => resolve(dialog.returnValue === 'confirm'), { once: true }));
  if (!confirmed || version !== state.version) return;
  if (sessionId === state.player?.sessionId) { await stopCurrentPlayer(); return; }
  state.mutations++;
  try {
    await state.client.request(`/api/v1/sessions/${encodeURIComponent(sessionId)}`, { method: 'DELETE' });
    await refreshSessions();
  } catch (error) { report(error); }
  finally { state.mutations--; }
}

async function refreshAll() {
  $('refresh-all').disabled = true;
  try { await Promise.all([refreshHealth(), refreshDevices(), refreshSessions()]); }
  finally { $('refresh-all').disabled = false; }
}

const actions = {
  settings: openSettings,
  'close-settings': () => $('settings-dialog').close(),
  'toggle-key': () => {
    const visible = $('apikey').type === 'password';
    $('apikey').type = visible ? 'text' : 'password';
    $('toggle-key').textContent = visible ? '隐藏' : '显示';
    $('toggle-key').setAttribute('aria-label', visible ? '隐藏 API Key' : '显示 API Key');
    $('toggle-key').setAttribute('aria-pressed', String(visible));
  },
  menu: () => {
    const opened = document.body.classList.toggle('sidebar-open');
    document.querySelector('.sidebar-mask').hidden = !opened;
    $('menu-toggle').setAttribute('aria-expanded', String(opened));
  },
  dismiss: () => { $('feedback').hidden = true; },
  refresh: refreshAll,
  devices: () => { if (requireKey()) return refreshDevices(); },
  sessions: () => { if (requireKey()) { state.authFailed = false; return refreshSessions(); } },
  reload: attachPlayer,
  'stop-player': stopCurrentPlayer,
  'save-current': () => saveFile(state.download?.sessionId),
  'save-session': button => saveFile(button.dataset.session),
  'stop-session': button => confirmStop(button.dataset.session),
  'page-prev': () => { state.sessionPage--; renderSessions(); },
  'page-next': () => { state.sessionPage++; renderSessions(); },
  'clear-log': () => { state.logs = []; $('log-count').textContent = '0'; renderLogs(); },
};

document.addEventListener('click', event => {
  const button = event.target.closest('button');
  if (!button || button.disabled) return;
  if (button.dataset.device !== undefined) selectDevice(button.dataset.device);
  if (button.dataset.range) fillRange(button.dataset.prefix, Number(button.dataset.range));
  const action = actions[button.dataset.action];
  if (action) Promise.resolve().then(() => action(button)).catch(report);
});
document.addEventListener('input', event => event.target.removeAttribute('aria-invalid'));
document.addEventListener('keydown', event => { if (event.key === 'Escape') closeMenu(); });
$('settings-form').addEventListener('submit', saveSettings);
$('preview-form').addEventListener('submit', event => { event.preventDefault(); void startMedia('PREVIEW'); });
$('playback-form').addEventListener('submit', event => { event.preventDefault(); void startMedia('PLAYBACK'); });
$('download-form').addEventListener('submit', event => { event.preventDefault(); void startDownload(); });
$('device-search').addEventListener('input', renderDevices);
$('log-level').addEventListener('change', renderLogs);
$('session-filter').addEventListener('submit', event => {
  event.preventDefault();
  state.filter = { search: $('session-search').value.trim().toLowerCase(), type: $('session-type').value, status: $('session-status').value };
  state.sessionPage = 1;
  renderSessions();
});
$('session-filter').addEventListener('reset', () => {
  state.filter = { search: '', type: '', status: '' };
  state.sessionPage = 1;
  renderSessions();
});
$('page-size').addEventListener('change', () => { state.sessionPage = 1; renderSessions(); });
$('auto-refresh').addEventListener('change', schedulePolling);
$('video').addEventListener('error', () => {
  if (state.player && !state.hls && $('video').getAttribute('src')) {
    showPlaceholder('视频播放失败', '请确认 HLS 地址可访问，并检查浏览器支持的音视频编码。');
    log('浏览器视频播放失败，请检查视频流和编码。', 'error');
  }
});
window.addEventListener('hashchange', navigate);
document.addEventListener('visibilitychange', () => {
  if (document.hidden) clearTimeout(state.pollTimer);
  else if (shouldPoll()) void refreshSessions(true);
});
window.addEventListener('pagehide', () => { clearTimeout(state.pollTimer); state.client.abortAll(); clearMedia(); });
window.addEventListener('pageshow', event => {
  if (event.persisted) { if (state.player) state.player.attached = false; void refreshAll(); }
});

$('current-base').textContent = state.config.base;
fillRange('pb', 10);
fillRange('dl', 10);
navigate();
renderLogs();
void refreshAll();