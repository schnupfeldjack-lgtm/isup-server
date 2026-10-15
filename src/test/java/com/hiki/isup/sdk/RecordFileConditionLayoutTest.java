package com.hiki.isup.sdk;

import com.sun.jna.Pointer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RecordFileConditionLayoutTest {

    // Official NET_EHOME_REC_FILE_COND: channel/type, two 12-byte times,
    // pagination, local/UTC flag, 63 reserved bytes. Total: 104 bytes.
    @Test
    void writesSearchConditionsAtNativeSdkOffsets() {
        HCISUPCMS.NET_EHOME_REC_FILE_COND condition = new HCISUPCMS.NET_EHOME_REC_FILE_COND();
        condition.dwChannel = 1;
        condition.dwRecType = 0xff;
        condition.struStartTime.wYear = 2026;
        condition.struStartTime.byMonth = 10;
        condition.struStartTime.byDay = 9;
        condition.struStartTime.byHour = 9;
        condition.struStopTime.wYear = 2026;
        condition.struStopTime.byMonth = 10;
        condition.struStopTime.byDay = 9;
        condition.struStopTime.byHour = 9;
        condition.struStopTime.bySecond = 30;
        condition.dwStartIndex = 3;
        condition.dwMaxFileCountPer = 8;
        condition.byLocalOrUTC = 1;
        condition.write();

        Pointer memory = condition.getPointer();
        assertEquals(104, condition.size());
        assertEquals(1, memory.getInt(0));
        assertEquals(0xff, memory.getInt(4));
        assertEquals(2026, memory.getShort(8));
        assertEquals(10, memory.getByte(10));
        assertEquals(9, memory.getByte(11));
        assertEquals(9, memory.getByte(12));
        assertEquals(2026, memory.getShort(20));
        assertEquals(30, memory.getByte(26));
        assertEquals(3, memory.getInt(32));
        assertEquals(8, memory.getInt(36));
        assertEquals(1, memory.getByte(40));
    }
}
