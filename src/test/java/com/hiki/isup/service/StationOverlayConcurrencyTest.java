package com.hiki.isup.service;

import com.hiki.isup.exception.IsupException;
import com.hiki.isup.model.DeviceChannel;
import com.hiki.isup.model.request.OverlayTextRequest;
import com.hiki.isup.model.response.OverlayTextResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StationOverlayConcurrencyTest {

    private final ChannelService channelService = mock(ChannelService.class);
    private final OverlayService overlayService = mock(OverlayService.class);
    private final StationOverlayService service = new StationOverlayService(channelService, overlayService);
    private final List<String> writes = new CopyOnWriteArrayList<>();

    @BeforeEach
    void channels() {
        when(channelService.listChannels(anyString())).thenReturn(List.of(
                new DeviceChannel(1, "打包台1全景"), new DeviceChannel(2, "打包台1细节"),
                new DeviceChannel(3, "打包台2全景"), new DeviceChannel(4, "打包台2细节")));
        when(overlayService.apply(anyString(), anyInt(), any())).thenAnswer(call -> {
            record(call.getArgument(0), call.getArgument(1), call.getArgument(2));
            return applied();
        });
    }

    @Test
    void newerShowWaitsUntilBothCamerasFinishIncludingChannelResolution() throws Exception {
        assertSameStationWaits("B", null, false);
    }

    @Test
    void clearWaitsUntilBothShowWritesFinishAndKeepsExpectedWaybill() throws Exception {
        assertSameStationWaits("", "A", false);
    }

    @Test
    void bothFailuresAreReportedAndNextRequestCanProceed() throws Exception {
        assertSameStationWaits("B", null, true);
    }

    private void assertSameStationWaits(String nextNumber, String expected, boolean failFirst) throws Exception {
        CountDownLatch firstPanorama = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch nextResolution = new CountDownLatch(1);
        AtomicInteger resolutions = new AtomicInteger();
        when(channelService.listChannels("D")).thenAnswer(call -> {
            if (resolutions.incrementAndGet() > 1) {
                nextResolution.countDown();
            }
            return List.of(new DeviceChannel(1, "打包台1全景"), new DeviceChannel(2, "打包台1细节"));
        });
        when(overlayService.apply(eq("D"), anyInt(), any())).thenAnswer(call -> {
            int channel = call.getArgument(1);
            OverlayTextRequest request = call.getArgument(2);
            record("D", channel, request);
            if ("A".equals(request.getWaybillNo())) {
                if (channel == 1) {
                    firstPanorama.countDown();
                    await(releaseFirst);
                }
                if (failFirst) {
                    throw new IllegalStateException("failure-" + channel);
                }
            } else {
                assertEquals(expected, request.getExpectedWaybillNo());
            }
            return applied();
        });
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> first = executor.submit(() -> service.apply(" D ", " 打包台1 ", "A"));
            await(firstPanorama);
            CountDownLatch nextStarted = new CountDownLatch(1);
            Future<?> next = executor.submit(() -> {
                nextStarted.countDown();
                service.apply("D", "打包台1", nextNumber, null, expected);
            });
            try {
                await(nextStarted);
                assertFalse(nextResolution.await(200, TimeUnit.MILLISECONDS),
                        "same station resolved channels while its earlier operation was still writing panorama");
            } finally {
                releaseFirst.countDown();
            }
            if (failFirst) {
                ExecutionException error = assertThrows(ExecutionException.class,
                        () -> first.get(3, TimeUnit.SECONDS));
                assertInstanceOf(IsupException.class, error.getCause());
                assertTrue(error.getCause().getMessage().contains("failure-1"));
                assertTrue(error.getCause().getMessage().contains("failure-2"));
            } else {
                first.get(3, TimeUnit.SECONDS);
            }
            next.get(3, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
        }
        assertEquals(List.of("D:1:A", "D:2:A", "D:1:" + nextNumber, "D:2:" + nextNumber), writes);
    }

    @Test
    void blockedStationDoesNotBlockAnotherStationOrDevice() throws Exception {
        CountDownLatch firstPanorama = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        when(overlayService.apply(eq("D"), eq(1), any())).thenAnswer(call -> {
            record("D", 1, call.getArgument(2));
            firstPanorama.countDown();
            await(releaseFirst);
            return applied();
        });
        try (ExecutorService executor = Executors.newFixedThreadPool(3)) {
            Future<?> first = executor.submit(() -> service.apply("D", "打包台1", "A"));
            try {
                await(firstPanorama);
                Future<?> otherStation = executor.submit(() -> service.apply("D", "打包台2", "B"));
                Future<?> otherDevice = executor.submit(() -> service.apply("E", "打包台1", "C"));
                otherStation.get(3, TimeUnit.SECONDS);
                otherDevice.get(3, TimeUnit.SECONDS);
                assertTrue(writes.containsAll(List.of("D:3:B", "D:4:B", "E:1:C", "E:2:C")));
            } finally {
                releaseFirst.countDown();
            }
            first.get(3, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
        }
    }

    @Test
    void slowChannelResolutionCannotLetLaterShowOvertake() throws Exception {
        CountDownLatch firstResolution = new CountDownLatch(1);
        CountDownLatch releaseResolution = new CountDownLatch(1);
        CountDownLatch nextResolution = new CountDownLatch(1);
        AtomicInteger resolutions = new AtomicInteger();
        when(channelService.listChannels("D")).thenAnswer(call -> {
            if (resolutions.incrementAndGet() == 1) {
                firstResolution.countDown();
                await(releaseResolution);
            } else {
                nextResolution.countDown();
            }
            return List.of(new DeviceChannel(1, "打包台1全景"), new DeviceChannel(2, "打包台1细节"));
        });
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> first = executor.submit(() -> service.apply("D", "打包台1", "A"));
            await(firstResolution);
            CountDownLatch nextStarted = new CountDownLatch(1);
            Future<?> next = executor.submit(() -> {
                nextStarted.countDown();
                service.apply("D", "打包台1", "B");
            });
            try {
                await(nextStarted);
                assertFalse(nextResolution.await(200, TimeUnit.MILLISECONDS),
                        "same station overtook an earlier request blocked in channel resolution");
            } finally {
                releaseResolution.countDown();
            }
            first.get(3, TimeUnit.SECONDS);
            next.get(3, TimeUnit.SECONDS);
        } finally {
            releaseResolution.countDown();
        }
        assertEquals(List.of("D:1:A", "D:2:A", "D:1:B", "D:2:B"), writes);
    }

    @Test
    void finishingOneCallCannotRetireTheLockStillUsedByItsWaiter() throws Exception {
        CountDownLatch firstPanorama = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondPanorama = new CountDownLatch(1);
        CountDownLatch releaseSecond = new CountDownLatch(1);
        CountDownLatch secondResolution = new CountDownLatch(1);
        CountDownLatch thirdResolution = new CountDownLatch(1);
        AtomicInteger resolutions = new AtomicInteger();
        when(channelService.listChannels("D")).thenAnswer(call -> {
            int count = resolutions.incrementAndGet();
            if (count == 2) {
                secondResolution.countDown();
            } else if (count == 3) {
                thirdResolution.countDown();
            }
            return List.of(new DeviceChannel(1, "打包台1全景"), new DeviceChannel(2, "打包台1细节"));
        });
        when(overlayService.apply(eq("D"), anyInt(), any())).thenAnswer(call -> {
            int channel = call.getArgument(1);
            OverlayTextRequest request = call.getArgument(2);
            record("D", channel, request);
            if (channel == 1 && "A".equals(request.getWaybillNo())) {
                firstPanorama.countDown();
                await(releaseFirst);
            } else if (channel == 1 && "B".equals(request.getWaybillNo())) {
                secondPanorama.countDown();
                await(releaseSecond);
            }
            return applied();
        });
        try (ExecutorService executor = Executors.newFixedThreadPool(3)) {
            Future<?> first = executor.submit(() -> service.apply("D", "打包台1", "A"));
            try {
                await(firstPanorama);
                CountDownLatch secondStarted = new CountDownLatch(1);
                Future<?> second = executor.submit(() -> {
                    secondStarted.countDown();
                    service.apply("D", "打包台1", "B");
                });
                await(secondStarted);
                assertFalse(secondResolution.await(200, TimeUnit.MILLISECONDS));
                releaseFirst.countDown();
                await(secondPanorama);
                first.get(3, TimeUnit.SECONDS);
                CountDownLatch thirdStarted = new CountDownLatch(1);
                Future<?> third = executor.submit(() -> {
                    thirdStarted.countDown();
                    service.apply("D", "打包台1", "C");
                });
                await(thirdStarted);
                assertFalse(thirdResolution.await(200, TimeUnit.MILLISECONDS),
                        "new caller acquired a different lock while an earlier waiter was still writing");
                releaseSecond.countDown();
                second.get(3, TimeUnit.SECONDS);
                third.get(3, TimeUnit.SECONDS);
            } finally {
                releaseFirst.countDown();
                releaseSecond.countDown();
            }
        } finally {
            releaseFirst.countDown();
            releaseSecond.countDown();
        }
        assertEquals(List.of("D:1:A", "D:2:A", "D:1:B", "D:2:B", "D:1:C", "D:2:C"), writes);
    }

    @Test
    void channelResolutionFailureReleasesTheStationForTheNextRequest() {
        when(channelService.listChannels("D"))
                .thenThrow(new IllegalStateException("channel lookup failed"))
                .thenReturn(List.of(new DeviceChannel(1, "打包台1全景"), new DeviceChannel(2, "打包台1细节")));
        assertThrows(IllegalStateException.class, () -> service.apply("D", "打包台1", "A"));
        service.apply("D", "打包台1", "B");
        assertEquals(List.of("D:1:B", "D:2:B"), writes);
    }

    private void record(String device, int channel, OverlayTextRequest request) {
        writes.add(device + ":" + channel + ":" + request.getWaybillNo());
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(3, TimeUnit.SECONDS), "timed out waiting for controlled operation");
    }

    private static OverlayTextResponse applied() {
        OverlayTextResponse response = new OverlayTextResponse();
        response.setApplied(true);
        return response;
    }
}
