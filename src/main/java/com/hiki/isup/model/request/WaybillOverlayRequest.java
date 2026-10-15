package com.hiki.isup.model.request;

/**
 * 工位单号及当次申请收件地址叠加请求。
 */
public class WaybillOverlayRequest {

    /** 工位名，如 {@code 打包台1}。服务端展开成「打包台1全景」和「打包台1细节」。 */
    private String channelCode;

    /** 单号。非空盖成「快递单号：」+ 单号；空串这两路只留「快递单号：」。 */
    private String waybillNo;

    private OverlayRecipient recipient;

    /** 当前包裹对应的平台订单号；缺失时不以内部订单号代替。 */
    private String platformOrderNo;

    public String getPlatformOrderNo() {
        return platformOrderNo;
    }

    public void setPlatformOrderNo(String platformOrderNo) {
        this.platformOrderNo = platformOrderNo;
    }

    /** 清除时校验当前画面的单号，防止上一单的出库请求清掉下一单。 */
    private String expectedWaybillNo;

    public OverlayRecipient getRecipient() {
        return recipient;
    }

    public void setRecipient(OverlayRecipient recipient) {
        this.recipient = recipient;
    }

    public String getExpectedWaybillNo() {
        return expectedWaybillNo;
    }

    public void setExpectedWaybillNo(String expectedWaybillNo) {
        this.expectedWaybillNo = expectedWaybillNo;
    }

    public String getChannelCode() {
        return channelCode;
    }

    public void setChannelCode(String channelCode) {
        this.channelCode = channelCode;
    }

    public String getWaybillNo() {
        return waybillNo;
    }

    public void setWaybillNo(String waybillNo) {
        this.waybillNo = waybillNo;
    }
}
