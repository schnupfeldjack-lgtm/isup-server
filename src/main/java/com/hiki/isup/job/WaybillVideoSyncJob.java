package com.hiki.isup.job;

import com.hiki.isup.service.WaybillVideoSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 默认每分钟同步一次出库包裹录像。单实例防重入：上一轮没跑完则跳过本轮。
 */
public class WaybillVideoSyncJob {

    private static final Logger log = LoggerFactory.getLogger(WaybillVideoSyncJob.class);

    private final WaybillVideoSyncService syncService;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public WaybillVideoSyncJob(WaybillVideoSyncService syncService) {
        this.syncService = syncService;
    }

    public void run() {
        // 固定停用，配置覆盖和直接调用都不能恢复自动下载/上传。
    }

    private void legacyRun() {
        if (!running.compareAndSet(false, true)) {
            log.warn("上一轮出库录像同步尚未结束，跳过本轮");
            return;
        }
        try {
            log.debug("开始出库录像同步");
            syncService.syncOnce();
        } catch (RuntimeException e) {
            log.error("出库录像同步任务异常", e);
        } finally {
            running.set(false);
        }
    }
}
