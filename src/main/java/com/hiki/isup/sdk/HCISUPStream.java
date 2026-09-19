package com.hiki.isup.sdk;

import com.sun.jna.Callback;
import com.sun.jna.Library;
import com.sun.jna.Pointer;

/**
 * HCISUPStream 动态库接口（ISUP 5.0 流媒体服务）。
 *
 * <p>负责：预览/回放监听、设备回连回调、码流数据回调。</p>
 */
public interface HCISUPStream extends Library {

    /** 码流头数据 */
    int NET_EHOME_SYSHEAD = 1;
    /** 码流数据（复合流 / 音视频分开的数据） */
    int NET_EHOME_STREAMDATA = 2;
    /** 码流结束标记 */
    int NET_EHOME_STREAMEND = 3;

    int NET_EHOME_DEVICEID_LEN = 256;
    int NET_EHOME_SERIAL_LEN = 12;

    // ==================== 预览 ====================

    class NET_EHOME_LISTEN_PREVIEW_CFG extends HikSdkStructure {
        public HCISUPCMS.NET_EHOME_IPADDRESS struIPAdress = new HCISUPCMS.NET_EHOME_IPADDRESS();
        public PREVIEW_NEWLINK_CB fnNewLinkCB;
        public Pointer pUser;
        public byte byLinkMode;       // 0-TCP，1-UDP，2-HRUDP
        public byte[] byRes = new byte[127];
    }

    class NET_EHOME_NEWLINK_CB_MSG extends HikSdkStructure {
        public byte[] szDeviceID = new byte[NET_EHOME_DEVICEID_LEN];
        public int iSessionID;
        public int dwChannelNo;
        public byte byStreamType;
        public byte[] byRes1 = new byte[3];
        public byte[] sDeviceSerial = new byte[NET_EHOME_SERIAL_LEN];
        public byte[] byRes = new byte[112];
    }

    class NET_EHOME_PREVIEW_CB_MSG extends HikSdkStructure {
        public byte byDataType;       // 1-码流头，2-码流数据
        public byte[] byRes1 = new byte[3];
        public Pointer pRecvdata;
        public int dwDataLen;
        public byte[] byRes2 = new byte[128];
    }

    class NET_EHOME_PREVIEW_DATA_CB_PARAM extends HikSdkStructure {
        public PREVIEW_DATA_CB fnPreviewDataCB;
        public Pointer pUserData;
        public byte[] byRes = new byte[128];
    }

    interface PREVIEW_NEWLINK_CB extends Callback {
        boolean invoke(int lLinkHandle, NET_EHOME_NEWLINK_CB_MSG pNewLinkCBMsg, Pointer pUserData);
    }

    interface PREVIEW_DATA_CB extends Callback {
        void invoke(int iPreviewHandle, NET_EHOME_PREVIEW_CB_MSG pPreviewCBMsg, Pointer pUserData);
    }

    // ==================== 回放 / 下载 ====================

    class NET_EHOME_PLAYBACK_LISTEN_PARAM extends HikSdkStructure {
        public HCISUPCMS.NET_EHOME_IPADDRESS struIPAdress = new HCISUPCMS.NET_EHOME_IPADDRESS();
        public PLAYBACK_NEWLINK_CB fnNewLinkCB;
        public Pointer pUser;
        public byte byLinkMode;       // 0-TCP，1-UDP，2-HRUDP
        public byte[] byRes = new byte[127];
    }

    class NET_EHOME_PLAYBACK_NEWLINK_CB_INFO extends HikSdkStructure {
        public byte[] szDeviceID = new byte[NET_EHOME_DEVICEID_LEN];
        public int lSessionID;        // 出参：设备分配的回放会话 ID
        public int dwChannelNo;       // 出参
        public byte[] sDeviceSerial = new byte[NET_EHOME_SERIAL_LEN];
        public byte byStreamFormat;   // 入参：0-PS，1-RTP
        public byte[] byRes1 = new byte[3];
        public PLAYBACK_DATA_CB fnPlayBackDataCB;
        public Pointer pUserData;
        public byte[] byRes = new byte[88];
    }

    class NET_EHOME_PLAYBACK_DATA_CB_PARAM extends HikSdkStructure {
        public PLAYBACK_DATA_CB fnPlayBackDataCB;
        public Pointer pUserData;
        public byte byStreamFormat;   // 0-PS，1-RTP
        public byte[] byRes = new byte[127];
    }

    class NET_EHOME_PLAYBACK_DATA_CB_INFO extends HikSdkStructure {
        public int dwType;            // 1-头信息，2-码流数据，3-回放停止信令
        public Pointer pData;
        public int dwDataLen;
        public byte[] byRes = new byte[128];
    }

    interface PLAYBACK_NEWLINK_CB extends Callback {
        boolean invoke(int lPlayBackLinkHandle, NET_EHOME_PLAYBACK_NEWLINK_CB_INFO pNewLinkCBMsg, Pointer pUserData);
    }

    interface PLAYBACK_DATA_CB extends Callback {
        boolean invoke(int iPlayBackLinkHandle, NET_EHOME_PLAYBACK_DATA_CB_INFO pDataCBInfo, Pointer pUserData);
    }

    // ==================== 接口方法 ====================

    boolean NET_ESTREAM_Init();

    boolean NET_ESTREAM_Fini();

    boolean NET_ESTREAM_SetSDKInitCfg(int enumType, Pointer lpInBuff);

    boolean NET_ESTREAM_SetSDKLocalCfg(int enumType, Pointer lpInBuff);

    int NET_ESTREAM_GetLastError();

    int NET_ESTREAM_GetBuildVersion();

    boolean NET_ESTREAM_SetLogToFile(int iLogLevel, String strLogDir, boolean bAutoDel);

    /** 开启预览监听，返回监听句柄 */
    int NET_ESTREAM_StartListenPreview(NET_EHOME_LISTEN_PREVIEW_CFG pListenParam);

    /** 开启回放监听，返回监听句柄 */
    int NET_ESTREAM_StartListenPlayBack(NET_EHOME_PLAYBACK_LISTEN_PARAM pListenParam);

    boolean NET_ESTREAM_StopListenPreview(int iListenHandle);

    boolean NET_ESTREAM_StopListenPlayBack(int iPlaybackListenHandle);

    boolean NET_ESTREAM_SetPreviewDataCB(int iHandle, NET_EHOME_PREVIEW_DATA_CB_PARAM pStruCBParam);

    boolean NET_ESTREAM_SetPlayBackDataCB(int iPlayBackLinkHandle, NET_EHOME_PLAYBACK_DATA_CB_PARAM pDataCBParam);

    boolean NET_ESTREAM_StopPreview(int iPreviewHandle);

    boolean NET_ESTREAM_StopPlayBack(int iPlayBackLinkHandle);
}
