package com.hiki.isup.model.request;

/** ERP 从对应面单申请的冻结请求提取的收件资料，不接收订单当前地址。 */
public record OverlayRecipient(String waybillNo, String applyRecordId, String name,
                               String country, String state, String city, String district,
                               String postcode, String address) {
}
