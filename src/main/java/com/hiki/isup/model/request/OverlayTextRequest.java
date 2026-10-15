package com.hiki.isup.model.request;

/**
 * 通道字符叠加（OSD）下发请求。
 *
 * <p>工位调用使用 {@link #waybillNo} 和当次申请 {@link #recipient}：非空显示单号及多行地址，
 * 空串或 null 清除业务内容，只保留「快递单号：」前缀。
 * {@link #external(String)} 构造默认请求，调用方可继续补充收件地址和清除校验单号。
 * {@link #text} 仍留给内部兼容调用。</p>
 *
 * <p>默认 overlayId=1。普通兼容请求沿用设备当前坐标；打包请求固定采用画面视觉对齐坐标，
 * 调用方传 positionX/Y 均不会覆盖服务端排版。durationSec=0。</p>
 */
public class OverlayTextRequest {

    /**
     * 单号，与收件地址一并显示。
     * 非空则画面显示「快递单号：」+ 单号；空串或 null 仍显示「快递单号：」。
     */
    private String waybillNo;

    private OverlayRecipient recipient;
    private String platformOrderNo;
    private String expectedWaybillNo;
    private boolean packingOverlay;
    private int addressLineWidth = 44;
    private int addressLineMaxBytes = 64;

    public OverlayRecipient getRecipient() { return recipient; }
    public void setRecipient(OverlayRecipient recipient) { this.recipient = recipient; }
    public String getPlatformOrderNo() { return platformOrderNo; }
    public void setPlatformOrderNo(String platformOrderNo) { this.platformOrderNo = platformOrderNo; }
    public String getExpectedWaybillNo() { return expectedWaybillNo; }
    public void setExpectedWaybillNo(String expectedWaybillNo) { this.expectedWaybillNo = expectedWaybillNo; }
    public boolean isPackingOverlay() { return packingOverlay; }
    public void setPackingOverlay(boolean packingOverlay) { this.packingOverlay = packingOverlay; }
    public int getAddressLineWidth() { return addressLineWidth; }
    public void setAddressLineWidth(int addressLineWidth) { this.addressLineWidth = addressLineWidth; }
    public int getAddressLineMaxBytes() { return addressLineMaxBytes; }
    public void setAddressLineMaxBytes(int addressLineMaxBytes) { this.addressLineMaxBytes = addressLineMaxBytes; }

    /**
     * 前缀，拼接在单号前面。
     * 默认 "快递单号："；传空串表示不加前缀。
     */
    private String prefix = "快递单号：";

    /**
     * 直接显示的完整文本（兼容字段）。
     * 同时传 waybillNo 与 text 时，优先 waybillNo + prefix。
     */
    private String text;

    /**
     * OSD 字符块 id（1~8，对应设备「字符叠加」页的 8 个输入框）。
     * 默认 1（已勾选的第一个字符块）。
     */
    private Integer overlayId = 1;

    /**
     * 是否启用该块叠加。默认 true。
     */
    private boolean enabled = true;

    /**
     * 位置横坐标。服务端忽略该字段；普通请求沿用设备坐标，打包请求使用固定视觉对齐坐标。
     */
    private Integer positionX;

    /**
     * 位置纵坐标。服务端忽略该字段；普通请求沿用设备坐标，打包请求使用固定连续行距。
     */
    private Integer positionY;

    /**
     * 显示持续秒数。
     *
     * <p>{@code 0}（默认）：一直显示，由外部再传空单号结束。
     * {@code >0} 时仍可预约到期后恢复成「快递单号：」。</p>
     */
    private int durationSec = 0;

    /**
     * 只返回待下发的 XML 而不真正写入设备，默认 false。
     * 首次对接建议先 true 核对一遍内容。
     */
    private boolean dryRun = false;

    /** 接收超时（毫秒），不传用服务端默认 5000 */
    private Integer timeoutMs;

    /**
     * 外部单号请求。非空盖上「快递单号：」+ 单号；空串或 null 只留「快递单号：」，叠加块保持开启。
     * 不自动倒计时，下一次调用才会改画面。
     */
    public static OverlayTextRequest external(String waybillNo) {
        OverlayTextRequest request = new OverlayTextRequest();
        request.setDurationSec(0);
        request.setWaybillNo(waybillNo == null ? "" : waybillNo.trim());
        request.setEnabled(true);
        request.setPackingOverlay(true);
        return request;
    }

    public String defaultText() {
        return prefix == null ? "" : prefix;
    }

    public String resolveDisplayText() {
        if (waybillNo != null) {
            String p = prefix == null ? "" : prefix;
            String no = waybillNo.trim();
            return no.isEmpty() ? p : p + no;
        }
        return text == null ? "" : text;
    }

    public String getWaybillNo() {
        return waybillNo;
    }

    public void setWaybillNo(String waybillNo) {
        this.waybillNo = waybillNo;
    }

    public String getPrefix() {
        return prefix;
    }

    public void setPrefix(String prefix) {
        this.prefix = prefix;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public Integer getOverlayId() {
        return overlayId;
    }

    public void setOverlayId(Integer overlayId) {
        this.overlayId = overlayId;
    }

    public Integer getPositionX() {
        return positionX;
    }

    public void setPositionX(Integer positionX) {
        this.positionX = positionX;
    }

    public Integer getPositionY() {
        return positionY;
    }

    public void setPositionY(Integer positionY) {
        this.positionY = positionY;
    }

    public int getDurationSec() {
        return durationSec;
    }

    public void setDurationSec(int durationSec) {
        this.durationSec = Math.max(0, durationSec);
    }

    public boolean isDryRun() {
        return dryRun;
    }

    public void setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
    }

public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Integer getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(Integer timeoutMs) {
        this.timeoutMs = timeoutMs;
    }
}
