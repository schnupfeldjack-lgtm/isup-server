package com.hiki.isup.service;

import com.hiki.isup.model.WaybillVideoRecord;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/** 只决定读取/提交顺序；领取 CAS、重试及设备准入仍由原流程负责。 */
final class PendingVideoScheduler {
    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    static final int TODAY_QUOTA = 3;
    private final Clock clock;
    private LocalDate quotaDay;
    private int todayStreak;

    PendingVideoScheduler() { this(Clock.system(ZONE)); }
    PendingVideoScheduler(Clock clock) { this.clock = Objects.requireNonNull(clock); }
    LocalDate today() { return LocalDate.ofInstant(clock.instant(), ZONE); }

    synchronized List<WaybillVideoRecord> select(LocalDate day, List<WaybillVideoRecord> current,
                                                List<WaybillVideoRecord> history, int limit,
                                                Set<String> excluded) {
        resetDay(day);
        Set<String> seen = new HashSet<>(excluded);
        Deque<WaybillVideoRecord> fresh = eligible(current, seen);
        Deque<WaybillVideoRecord> old = eligible(history, seen);
        List<WaybillVideoRecord> result = new ArrayList<>();
        int simulatedStreak = todayStreak;
        while (result.size() < limit && (!fresh.isEmpty() || !old.isEmpty())) {
            if (!fresh.isEmpty() && (simulatedStreak < TODAY_QUOTA || old.isEmpty())) {
                result.add(fresh.removeFirst());
                simulatedStreak = Math.min(TODAY_QUOTA, simulatedStreak + 1);
            } else {
                result.add(old.removeFirst());
                simulatedStreak = 0;
            }
        }
        return result;
    }

    private Deque<WaybillVideoRecord> eligible(List<WaybillVideoRecord> rows, Set<String> seen) {
        Deque<WaybillVideoRecord> result = new ArrayDeque<>();
        for (WaybillVideoRecord row : rows) if (seen.add(row.getId())) result.addLast(row);
        return result;
    }

    /** 仅排程线程在 workers.execute 成功返回后调用，失败/CAS 拒绝同样算一次处理机会。 */
    synchronized void submitted(WaybillVideoRecord row) {
        LocalDate day = today();
        resetDay(day);
        todayStreak = row.getStartTime() != null && row.getStartTime().toLocalDate().equals(day)
                ? Math.min(TODAY_QUOTA, todayStreak + 1) : 0;
    }

    private void resetDay(LocalDate day) {
        if (!day.equals(quotaDay)) { quotaDay = day; todayStreak = 0; }
    }

    synchronized Map<String, Object> snapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", "CURRENT_DAY_3_HISTORY_1");
        result.put("timezone", ZONE.getId());
        result.put("day", quotaDay);
        result.put("todayQuota", TODAY_QUOTA);
        result.put("historyQuota", 1);
        result.put("todayStreak", todayStreak);
        return result;
    }
}
