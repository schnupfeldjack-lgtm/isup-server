package com.hiki.isup.sdk;

import com.sun.jna.Callback;
import com.sun.jna.Library;
import com.sun.jna.Pointer;
import com.sun.jna.Union;

import java.util.Arrays;
import java.util.List;

/**
 * HCISUPCMS 动态库接口（ISUP 5.0 注册 / 信令服务）。
 *
 * <p>方法签名与结构体定义均对照成熟实现（ruoyi-haikang-isup / 海康官方 Demo），
 * 未做任何猜测性修改。仅保留本项目需要的四类能力：
 * 初始化、注册监听、实时预览、按时间回放/下载。</p>
 */
public interface HCISUPCMS extends Library {

    // ==================== 常量 ====================
    int MAX_MASTER_KEY_LEN = 16;
    int MAX_DEVICE_ID_LEN = 256;
    int MAX_DEVNAME_LEN_EX = 64;
    int MAX_FULL_SERIAL_NUM_LEN = 64;
    int NET_EHOME_SERIAL_LEN = 12;

    /** SDK 初始化配置类型：0-libcrypto 路径，1-libssl 路径 */
    int SDK_INIT_CFG_CRYPTO = 0;
    int SDK_INIT_CFG_SSL = 1;
    /** 本地配置类型：5-HCAapSDKCom 组件库目录 */
    int SDK_LOCAL_CFG_COMPONENT_DIR = 5;

    /** 设备注册回调类型 */
    interface EHOME_REGISTER_TYPE {
        int ENUM_DEV_ON = 0;                    // 设备上线
        int ENUM_DEV_OFF = 1;                   // 设备下线
        int ENUM_DEV_ADDRESS_CHANGED = 2;       // 设备地址变化
        int ENUM_DEV_AUTH = 3;                  // ISUP5.0 设备认证
        int ENUM_DEV_SESSIONKEY = 4;            // ISUP5.0 SessionKey
        int ENUM_DEV_DAS_REQ = 5;               // ISUP5.0 重定向(DAS)请求
        int ENUM_DEV_SESSIONKEY_REQ = 6;        // SessionKey 请求
        int ENUM_DEV_DAS_REREGISTER = 7;        // 设备重注册
        int ENUM_DEV_DAS_PINGREO = 8;           // 注册心跳
        int ENUM_DEV_DAS_EHOMEKEY_ERROR = 9;    // 密钥校验失败
        int ENUM_DEV_SESSIONKEY_ERROR = 10;     // SessionKey 交互异常
        int ENUM_DEV_SLEEP = 11;                // 设备休眠
    }

    // ==================== 结构体 ====================

    class NET_EHOME_IPADDRESS extends HikSdkStructure {
        public byte[] szIP = new byte[128];
        public short wPort;
        public byte[] byRes = new byte[2];
    }

    class NET_EHOME_DEV_REG_INFO extends HikSdkStructure {
        public int dwSize;
        public int dwNetUnitType;
        public byte[] byDeviceID = new byte[MAX_DEVICE_ID_LEN];
        public byte[] byFirmwareVersion = new byte[24];
        public NET_EHOME_IPADDRESS struDevAdd = new NET_EHOME_IPADDRESS();
        public int dwDevType;
        public int dwManufacture;
        public byte[] byPassWord = new byte[32];
        public byte[] sDeviceSerial = new byte[NET_EHOME_SERIAL_LEN];
        public byte byReliableTransmission;
        public byte byWebSocketTransmission;
        public byte bySupportRedirect;
        public byte[] byDevProtocolVersion = new byte[6];
        public byte[] bySessionKey = new byte[16];
        public byte byMarketType;
        public byte[] byRes = new byte[26];
    }

    class NET_EHOME_DEV_REG_INFO_V12 extends HikSdkStructure {
        public NET_EHOME_DEV_REG_INFO struRegInfo = new NET_EHOME_DEV_REG_INFO();
        public NET_EHOME_IPADDRESS struRegAddr = new NET_EHOME_IPADDRESS();
        public byte[] sDevName = new byte[MAX_DEVNAME_LEN_EX];
        public byte[] byDeviceFullSerial = new byte[MAX_FULL_SERIAL_NUM_LEN];
        public byte[] byRes = new byte[128];
    }

    class NET_EHOME_DEV_SESSIONKEY extends HikSdkStructure {
        public byte[] sDeviceID = new byte[MAX_DEVICE_ID_LEN];
        public byte[] sSessionKey = new byte[MAX_MASTER_KEY_LEN];
    }

    class NET_EHOME_BLACKLIST_SEVER extends HikSdkStructure {
        public NET_EHOME_IPADDRESS struAdd = new NET_EHOME_IPADDRESS();
        public byte[] byServerName = new byte[32];
        public byte[] byUserName = new byte[32];
        public byte[] byPassWord = new byte[32];
        public byte[] byRes = new byte[64];
    }

    /** 注册成功后回传给设备的服务器信息（ISUP 5.0） */
    class NET_EHOME_SERVER_INFO_V50 extends HikSdkStructure {
        public int dwSize;
        public int dwKeepAliveSec;
        public int dwTimeOutCount;
        public NET_EHOME_IPADDRESS struTCPAlarmSever = new NET_EHOME_IPADDRESS();
        public NET_EHOME_IPADDRESS struUDPAlarmSever = new NET_EHOME_IPADDRESS();
        public int dwAlarmServerType;
        public NET_EHOME_IPADDRESS struNTPSever = new NET_EHOME_IPADDRESS();
        public int dwNTPInterval;
        public NET_EHOME_IPADDRESS struPictureSever = new NET_EHOME_IPADDRESS();
        public int dwPicServerType;
        public NET_EHOME_BLACKLIST_SEVER struBlackListServer = new NET_EHOME_BLACKLIST_SEVER();
        public NET_EHOME_IPADDRESS struRedirectSever = new NET_EHOME_IPADDRESS();
        public byte[] byClouldAccessKey = new byte[64];
        public byte[] byClouldSecretKey = new byte[64];
        public byte byClouldHttps;
        public byte[] byRes1 = new byte[3];
        public int dwAlarmKeepAliveSec;
        public int dwAlarmTimeOutCount;
        public int dwClouldPoolId;
        public byte[] byRes = new byte[368];
    }

    /** 设备注册回调 */
    interface DEVICE_REGISTER_CB extends Callback {
        boolean invoke(int lUserID, int dwDataType, Pointer pOutBuffer,
                       int dwOutLen, Pointer pInBuffer, int dwInLen, Pointer pUser);
    }

    class NET_EHOME_CMS_LISTEN_PARAM extends HikSdkStructure {
        public NET_EHOME_IPADDRESS struAddress = new NET_EHOME_IPADDRESS();
        public DEVICE_REGISTER_CB fnCB;
        public Pointer pUserData;
        public int dwKeepAliveSec;
        public int dwTimeOutCount;
        public byte[] byRes = new byte[24];
    }

    // ---- 预览 ----

    class NET_EHOME_PREVIEWINFO_IN_V11 extends HikSdkStructure {
        public int iChannel;
        public int dwStreamType;
        public int dwLinkMode;
        public NET_EHOME_IPADDRESS struStreamSever = new NET_EHOME_IPADDRESS();
        public byte byDelayPreview;
        public byte[] byRes = new byte[31];
    }

    class NET_EHOME_PREVIEWINFO_OUT extends HikSdkStructure {
        public int lSessionID;
        public byte[] byRes = new byte[128];
    }

    class NET_EHOME_PUSHSTREAM_IN extends HikSdkStructure {
        public int dwSize;
        public int lSessionID;
        public byte[] byRes = new byte[128];
    }

    class NET_EHOME_PUSHSTREAM_OUT extends HikSdkStructure {
        public int dwSize;
        public byte[] byRes = new byte[128];
    }

    // ---- 回放 / 下载 ----

    class NET_EHOME_TIME extends HikSdkStructure {
        public short wYear;
        public byte byMonth;
        public byte byDay;
        public byte byHour;
        public byte byMinute;
        public byte bySecond;
        public byte byRes1;
        public short wMSecond;
        public byte[] byRes2 = new byte[2];
    }

    class NET_EHOME_PLAYBACKBYNAME extends HikSdkStructure {
        public byte[] szFileName = new byte[100];
        public int dwSeekType;
        public int dwFileOffset;
        public int dwFileSpan;
    }

    class NET_EHOME_PLAYBACKBYTIME extends HikSdkStructure {
        public NET_EHOME_TIME struStartTime = new NET_EHOME_TIME();
        public NET_EHOME_TIME struStopTime = new NET_EHOME_TIME();
        public byte byLocalOrUTC;
        public byte byDuplicateSegment;
    }

    class NET_EHOME_PLAYBACKMODE extends Union {
        public byte[] byLen = new byte[512];
        public NET_EHOME_PLAYBACKBYNAME struPlayBackbyName;
        public NET_EHOME_PLAYBACKBYTIME struPlayBackbyTime;
    }

    class NET_EHOME_PLAYBACK_INFO_IN extends HikSdkStructure {
        public int dwSize;
        public int dwChannel;
        public byte byPlayBackMode;     // 0-按名字，1-按时间
        public byte byStreamPackage;    // 0-PS（默认），1-RTP
        public byte[] byRes = new byte[2];
        public NET_EHOME_PLAYBACKMODE unionPlayBackMode;
        public NET_EHOME_IPADDRESS struStreamSever = new NET_EHOME_IPADDRESS();
    }

    class NET_EHOME_PLAYBACK_INFO_OUT extends HikSdkStructure {
        public int lSessionID;
        public int lHandle;
        public byte[] byRes = new byte[124];
    }

    class NET_EHOME_PUSHPLAYBACK_IN extends HikSdkStructure {
        public int dwSize;
        public int lSessionID;
        public byte[] byKeyMD5 = new byte[32];
        public byte[] byRes = new byte[96];
    }

    class NET_EHOME_PUSHPLAYBACK_OUT extends HikSdkStructure {
        public int dwSize;
        public int lHandle;
        public byte[] byRes = new byte[124];
    }

    /** 用于向 SDK 传递字符串路径（crypto / ssl / 组件库目录） */
    class BYTE_ARRAY extends HikSdkStructure {
        public byte[] byValue;

        public BYTE_ARRAY(int iLen) {
            byValue = new byte[iLen];
        }

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("byValue");
        }
    }

    // ==================== 接口方法 ====================

    boolean NET_ECMS_Init();

    boolean NET_ECMS_Fini();

    boolean NET_ECMS_SetSDKInitCfg(int enumType, Pointer lpInBuff);

    boolean NET_ECMS_SetSDKLocalCfg(int enumType, Pointer lpInBuff);

    int NET_ECMS_GetLastError();

    int NET_ECMS_GetBuildVersion();

    boolean NET_ECMS_SetLogToFile(int iLogLevel, String strLogDir, boolean bAutoDel);

    boolean NET_ECMS_SetDeviceSessionKey(Pointer pDeviceKey);

    /** 开启 CMS 监听，返回监听句柄；失败返回负数 */
    int NET_ECMS_StartListen(NET_EHOME_CMS_LISTEN_PARAM lpCMSListenPara);

    boolean NET_ECMS_StopListen(int iHandle);

    boolean NET_ECMS_ForceLogout(int lUserID);

    boolean NET_ECMS_StartGetRealStreamV11(int lUserID,
                                           NET_EHOME_PREVIEWINFO_IN_V11 pPreviewInfoIn,
                                           NET_EHOME_PREVIEWINFO_OUT pPreviewInfoOut);

    boolean NET_ECMS_StopGetRealStream(int lUserID, int lSessionID);

    boolean NET_ECMS_StartPushRealStream(int lUserID,
                                         NET_EHOME_PUSHSTREAM_IN pPushInfoIn,
                                         NET_EHOME_PUSHSTREAM_OUT pPushInfoOut);

    boolean NET_ECMS_StartPlayBack(int lUserID,
                                   NET_EHOME_PLAYBACK_INFO_IN pPlaybackInfoIn,
                                   NET_EHOME_PLAYBACK_INFO_OUT pPlaybackInfoOut);

    boolean NET_ECMS_StartPushPlayBack(int lUserID,
                                       NET_EHOME_PUSHPLAYBACK_IN struPushPlayBackIn,
                                       NET_EHOME_PUSHPLAYBACK_OUT struPushPlayBackOut);

    boolean NET_ECMS_StopPlayBack(int lUserID, int lSessionID);
}
