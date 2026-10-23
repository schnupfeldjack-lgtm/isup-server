package com.hiki.isup.service;

import com.hiki.isup.config.IsupProperties;
import com.hiki.isup.repository.WaybillVideoRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class WaybillVideoRecoveryTest {
    @Test
    void freezesPollingOnlyAfterNativeAndAuthorizationChecksPass() {
        SessionManager sessions = mock(SessionManager.class);
        WaybillVideoRepository repository = mock(WaybillVideoRepository.class);
        try (WaybillVideoSyncService service = new WaybillVideoSyncService(new IsupProperties(),
                mock(DownloadService.class), sessions, mock(OssService.class), repository,
                mock(ChannelService.class), mock(DeviceRegistry.class), mock(RecordingRemuxer.class))) {
            AtomicBoolean authorized = new AtomicBoolean();
            assertFalse(service.prepareProcessRecovery(600_000, () -> { authorized.set(true); return true; }));
            assertFalse(authorized.get());
            when(sessions.canRecoverProcess(anyLong())).thenReturn(true);
            assertFalse(service.prepareProcessRecovery(600_000, () -> false));
            verify(sessions, never()).prepareProcessRecovery(anyLong());
            ReflectionTestUtils.setField(service, "running", true);
            assertFalse(service.prepareProcessRecovery(600_000, () -> fail("still processing")));
            ReflectionTestUtils.setField(service, "running", false);
            when(sessions.prepareProcessRecovery(anyLong())).thenReturn(true);
            assertTrue(service.prepareProcessRecovery(600_000, () -> true));
            service.syncOnce();
            verifyNoInteractions(repository);
            assertFalse(service.prepareProcessRecovery(600_000, () -> fail("already closed")));
        }
    }
}
