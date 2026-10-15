package com.hiki.isup.job;

import com.hiki.isup.service.WaybillVideoSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 默认每分钟同步一次出库包裹录像。单实例防重入：上一轮没跑完则跳过本轮。
 */
@Component
@ConditionalOnProperty(prefix = "isup.waybill-video", name = "enabled", havingValue = "true")
public class WaybillVideoSyncJob {

    private static final Logger log = LoggerFactory.getLogger(WaybillVideoSyncJob.class);

    private final WaybillVideoSyncService syncService;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public WaybillVideoSyncJob(WaybillVideoSyncService syncService) {
        this.syncService = syncService;
    }

    @Scheduled(cron = "${isup.waybill-video.cron:0 * * * * *}")
    public void run() {
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
