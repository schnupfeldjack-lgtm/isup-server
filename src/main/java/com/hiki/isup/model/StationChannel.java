package com.hiki.isup.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 一个打包台：通道名去掉末尾「全景 / 细节」后的工位，带上两路通道号。
 */
public class StationChannel {

    private String channelCode;

    /** 全景通道号；这一边没配或配重了则为 null */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private Integer panoramaChannel;

    /** 细节通道号；这一边没配或配重了则为 null */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private Integer detailChannel;

    public StationChannel() {
    }

    public StationChannel(String channelCode, Integer panoramaChannel, Integer detailChannel) {
        this.channelCode = channelCode;
        this.panoramaChannel = panoramaChannel;
        this.detailChannel = detailChannel;
    }

    public String getChannelCode() {
        return channelCode;
    }

    public void setChannelCode(String channelCode) {
        this.channelCode = channelCode;
    }

    public Integer getPanoramaChannel() {
        return panoramaChannel;
    }

    public void setPanoramaChannel(Integer panoramaChannel) {
        this.panoramaChannel = panoramaChannel;
    }

    public Integer getDetailChannel() {
        return detailChannel;
    }

    public void setDetailChannel(Integer detailChannel) {
        this.detailChannel = detailChannel;
    }
}
