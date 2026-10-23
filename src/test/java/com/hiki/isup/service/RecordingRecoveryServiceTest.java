package com.hiki.isup.service;

import com.hiki.isup.model.DeviceChannel;
import com.hiki.isup.model.DeviceInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RecordingRecoveryServiceTest {
    private final SessionManager sessions = mock(SessionManager.class);
    private final WaybillVideoSyncService sync = mock(WaybillVideoSyncService.class);
    private final OverlayService overlays = mock(OverlayService.class);
    private final DeviceRegistry devices = mock(DeviceRegistry.class);
    private final ChannelService channels = mock(ChannelService.class);
    private final DeviceIsapiClient isapi = mock(DeviceIsapiClient.class);
    private final RecordingRecoveryGate gate = mock(RecordingRecoveryGate.class);
    private final RecordingRecoveryBudget budget = mock(RecordingRecoveryBudget.class);
    private final AtomicInteger exits = new AtomicInteger();
    private RecordingRecoveryService service;

    @BeforeEach
    void setUp() throws IOException {
        service = new RecordingRecoveryService(sessions, sync, overlays, devices, channels, isapi,
                gate, budget, exits::incrementAndGet);
        ReflectionTestUtils.setField(service, "enabled", true);
        lenient().when(sessions.canRecoverProcess(anyLong())).thenReturn(true);
        lenient().when(budget.available()).thenReturn(true);
        lenient().when(budget.reserve()).thenReturn(true);
        lenient().when(gate.tryQuiesce(anyLong(), anyLong(), any())).thenAnswer(i -> ((BooleanSupplier) i.getArgument(2)).getAsBoolean());
        lenient().when(overlays.tryQuiesceForRecovery(any())).thenAnswer(i -> ((BooleanSupplier) i.getArgument(0)).getAsBoolean());
        lenient().when(sync.prepareProcessRecovery(anyLong(), any())).thenAnswer(i -> ((BooleanSupplier) i.getArgument(1)).getAsBoolean());
        lenient().when(devices.onlineDeviceIds()).thenReturn(List.of("DLWH"));
        lenient().when(devices.get("DLWH")).thenReturn(new DeviceInfo("DLWH", 7, "127.0.0.1"));
        lenient().when(channels.listChannels("DLWH")).thenReturn(List.of(
                new DeviceChannel(1, "打包台1全景"), new DeviceChannel(2, "打包台1细节")));
        lenient().when(isapi.get(eq("DLWH"), anyString(), anyInt())).thenReturn(clearOverlay());
    }

    @Test
    void exitsOnceOnlyAfterBothChannelsAndDurableBudgetPass() throws IOException {
        service.checkRecovery();
        service.checkRecovery();
        assertEquals(1, exits.get());
        verify(budget, times(1)).reserve();
        verify(isapi).get("DLWH", "/ISAPI/System/Video/inputs/channels/1/overlays", 2000);
        verify(isapi).get("DLWH", "/ISAPI/System/Video/inputs/channels/2/overlays", 2000);
        var order = inOrder(budget, gate, overlays, sync);
        order.verify(budget).available();
        order.verify(gate).tryQuiesce(anyLong(), anyLong(), any());
        order.verify(overlays).tryQuiesceForRecovery(any());
        order.verify(sync).prepareProcessRecovery(anyLong(), any());
        order.verify(budget).reserve();
    }

    @Test
    void packingInProgressDoesNotConsumeBudgetOrExit() throws IOException {
        when(isapi.get(eq("DLWH"), contains("/2/"), anyInt()))
                .thenReturn(clearOverlay().replace("快递单号：", "快递单号：YLUS123"));
        service.checkRecovery();
        verify(budget, never()).reserve();
        assertEquals(0, exits.get());
    }

    @Test
    void unknownDeviceChannelOrMalformedOverlayFailsClosed() throws IOException {
        when(channels.listChannels("DLWH")).thenReturn(List.of(new DeviceChannel(1, "打包台1全景")));
        service.checkRecovery();
        verify(budget, never()).reserve();
        when(channels.listChannels("DLWH")).thenReturn(List.of(
                new DeviceChannel(1, "打包台1全景"), new DeviceChannel(2, "打包台1细节")));
        when(isapi.get(anyString(), anyString(), anyInt())).thenReturn("<invalid/>");
        service.checkRecovery();
        assertEquals(0, exits.get());
        verify(budget, never()).reserve();
    }

    @Test
    void unreadableBudgetAndCooldownCannotTriggerExit() throws IOException {
        when(budget.available()).thenReturn(false);
        service.checkRecovery();
        verifyNoInteractions(gate);
        when(budget.available()).thenReturn(true);
        when(budget.reserve()).thenThrow(new IOException("disk unavailable"));
        service.checkRecovery();
        assertEquals(0, exits.get());
        assertTrue(service.diagnostics().toString().contains("disk unavailable"));
    }

    @Test
    void disabledOrNotQuiescentNeverProbesTheDevice() {
        ReflectionTestUtils.setField(service, "enabled", false);
        service.checkRecovery();
        ReflectionTestUtils.setField(service, "enabled", true);
        when(sessions.canRecoverProcess(anyLong())).thenReturn(false);
        service.checkRecovery();
        verifyNoInteractions(isapi, budget);
        assertEquals(0, exits.get());
    }

    @Test
    void reconnectAfterReadbackInvalidatesRecovery() throws IOException {
        doAnswer(i -> {
            when(devices.get("DLWH")).thenReturn(new DeviceInfo("DLWH", 7, "127.0.0.1"));
            return ((BooleanSupplier) i.getArgument(2)).getAsBoolean();
        }).when(gate).tryQuiesce(anyLong(), anyLong(), any());
        service.checkRecovery();
        verify(budget, never()).reserve();
        assertEquals(0, exits.get());
    }

    @Test
    void exitBindingFailureLeavesAdmissionAndBudgetUntouched() {
        LinuxRecoveryExit exit = new LinuxRecoveryExit(() -> { throw new UnsatisfiedLinkError("missing _exit"); });
        service = new RecordingRecoveryService(sessions, sync, overlays, devices, channels, isapi,
                gate, budget, exit);
        ReflectionTestUtils.setField(service, "enabled", true);
        service.checkRecovery();
        assertEquals("RECOVERY_BLOCKED", service.diagnostics().get("state"));
        assertEquals(false, service.diagnostics().get("exitRequested"));
        verifyNoInteractions(gate, budget, overlays, sync, devices, channels, isapi);
    }

    @Test
    void disabledOrNoCandidateDoesNotResolveNativeExit() {
        AtomicInteger resolutions = new AtomicInteger();
        LinuxRecoveryExit exit = new LinuxRecoveryExit(() -> {
            resolutions.incrementAndGet();
            throw new UnsatisfiedLinkError("must not resolve");
        });
        service = new RecordingRecoveryService(sessions, sync, overlays, devices, channels, isapi,
                gate, budget, exit);
        service.checkRecovery();
        ReflectionTestUtils.setField(service, "enabled", true);
        when(sessions.canRecoverProcess(anyLong())).thenReturn(false);
        service.checkRecovery();
        assertEquals(0, resolutions.get());
        verifyNoInteractions(gate, budget);
    }
    private static String clearOverlay() {
        return "<VideoOverlay xmlns=\"http://www.hikvision.com/ver20/XMLSchema\"><TextOverlayList>"
                + "<TextOverlay><id>1</id><enabled>true</enabled><displayText>快递单号：</displayText></TextOverlay>"
                + "</TextOverlayList></VideoOverlay>";
    }
}
