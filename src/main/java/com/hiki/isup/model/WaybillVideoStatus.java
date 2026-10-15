package com.hiki.isup.model;

/**
 * {@code wms_outbound_waybill_video.status} 取值。与 ERP 约定用这些字面量，不要改大小写。
 */
public enum WaybillVideoStatus {
    PENDING,
    PROCESSING,
    SUCCESS,
    FAILED
}
