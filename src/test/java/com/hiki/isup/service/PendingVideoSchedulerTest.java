package com.hiki.isup.service;

import com.hiki.isup.model.WaybillVideoRecord;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PendingVideoSchedulerTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 21);

    private Object scheduler(Clock clock) throws Exception {
        var ctor = Class.forName("com.hiki.isup.service.PendingVideoScheduler").getDeclaredConstructor(Clock.class);
        ctor.setAccessible(true);
        return ctor.newInstance(clock);
    }
    private Object scheduler() throws Exception {
        return scheduler(Clock.fixed(DAY.atTime(12, 0).atZone(ZONE).toInstant(), ZoneOffset.UTC));
    }
    private WaybillVideoRecord row(String id, LocalDate day) {
        var r = new WaybillVideoRecord(); r.setId(id);
        r.setStartTime(day.atTime(9, 0)); return r;
    }
    private List<WaybillVideoRecord> lane(String prefix, LocalDate day, int count) {
        return java.util.stream.IntStream.range(0, count).mapToObj(i -> row(prefix+i, day)).toList();
    }
    private List<WaybillVideoRecord> mix(Object s, List<WaybillVideoRecord> today, List<WaybillVideoRecord> old, int n, Set<String> excluded) {
        return ReflectionTestUtils.invokeMethod(s, "select", DAY, today, old, n, excluded);
    }
    private void submitted(Object s, WaybillVideoRecord row) {
        ReflectionTestUtils.invokeMethod(s, "submitted", row);
    }

    @Test void threeTodayThenHistoryAcrossBatchSizesAndRounds() throws Exception {
        for (int size : new int[]{1,2,5,8}) {
            Object s=scheduler(); var today=new ArrayList<>(lane("T",DAY,30));
            var old=new ArrayList<>(lane("H",DAY.minusDays(1),30)); var actual=new ArrayList<String>();
            while(actual.size()<24) {
                for(var r:mix(s,today,old,size,Set.of())) {
                    actual.add(r.getId().substring(0,1)); submitted(s,r); today.remove(r); old.remove(r);
                    if(actual.size()==24) break;
                }
            }
            assertEquals("TTTH".repeat(6),String.join("",actual));
        }
    }
    @Test void repeatedReadOnlySamplesDoNotConsumeQuota() throws Exception {
        Object s=scheduler(); var today=lane("T",DAY,6); var old=lane("H",DAY.minusDays(1),6);
        assertEquals(mix(s,today,old,5,Set.of()),mix(s,today,old,5,Set.of()));
        assertEquals(List.of(today.get(0),today.get(1),today.get(2),old.get(0),today.get(3)),mix(s,today,old,5,Set.of()));
    }
    @Test void excludedRowsCannotHideAvailableOtherLaneOrConsumeQuota() throws Exception {
        Object s=scheduler(); var today=lane("T",DAY,5); var old=lane("H",DAY.minusDays(1),5);
        assertEquals(old,mix(s,today,old,5,Set.of("T0","T1","T2","T3","T4")));
        assertEquals(today.getFirst(),mix(s,today,old,1,Set.of()).getFirst());
    }
    @Test void aSingleLaneUsesAllSlotsAndReturningLaneReceivesItsTurn() throws Exception {
        Object s=scheduler(); var today=lane("T",DAY,8); var old=lane("H",DAY.minusDays(1),8);
        assertEquals(today,mix(s,today,List.of(),8,Set.of()));
        today.forEach(r->submitted(s,r));
        assertEquals(old.getFirst(),mix(s,today,old,1,Set.of()).getFirst());
        submitted(s,old.getFirst());
        assertEquals(today.getFirst(),mix(s,today,old,1,Set.of()).getFirst());
        assertEquals(old,mix(s,List.of(),old,8,Set.of()));
    }
    @Test void usesShanghaiDateEvenWhenClockZoneIsUtc() throws Exception {
        Object s=scheduler(Clock.fixed(Instant.parse("2026-10-20T16:00:01Z"),ZoneOffset.UTC));
        assertEquals(DAY,ReflectionTestUtils.invokeMethod(s,"today"));
    }
    @Test void midnightResetsQuotaAndYesterdaySubmissionIsHistorical() throws Exception {
        var instant=new java.util.concurrent.atomic.AtomicReference<>(Instant.parse("2026-10-21T15:59:59Z"));
        Clock clock=new Clock() {
            public ZoneId getZone(){return ZoneOffset.UTC;}
            public Clock withZone(ZoneId z){return this;}
            public Instant instant(){return instant.get();}
        };
        Object s=scheduler(clock); lane("T",DAY,3).forEach(r->submitted(s,r));
        instant.set(Instant.parse("2026-10-21T16:00:01Z"));
        submitted(s,row("yesterday",DAY));
        LocalDate next=DAY.plusDays(1);
        List<WaybillVideoRecord> selection=ReflectionTestUtils.invokeMethod(s,"select",next,lane("N",next,4),lane("H",DAY,4),4,Set.of());
        assertEquals(List.of("N0","N1","N2","H0"),selection.stream().map(WaybillVideoRecord::getId).toList());
    }
}
