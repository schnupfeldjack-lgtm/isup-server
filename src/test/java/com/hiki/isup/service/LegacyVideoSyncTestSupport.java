package com.hiki.isup.service;

import org.springframework.test.util.ReflectionTestUtils;

/** 历史算法的隔离回归；生产入口固定禁用，不能由这些测试推断同步仍可使用。 */
final class LegacyVideoSyncTestSupport {
    static void syncOnce(WaybillVideoSyncService service) {
        ReflectionTestUtils.invokeMethod(service, "legacySyncOnce");
    }
    static Object releaseRetry(WaybillVideoSyncService service, String id, String revision,
                               String fingerprint, String reason) {
        return ReflectionTestUtils.invokeMethod(service, "legacyReleaseRetry", id, revision, fingerprint, reason);
    }
}
