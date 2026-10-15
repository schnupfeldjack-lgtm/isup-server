package com.hiki.isup.callback;

import com.hiki.isup.config.IsupProperties;
import com.hiki.isup.sdk.HCISUPCMS;
import com.hiki.isup.sdk.NativeSdkLoader;
import com.hiki.isup.service.DeviceRegistry;
import com.hiki.isup.service.SessionManager;
import com.sun.jna.Pointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static com.hiki.isup.sdk.NativeSdkLoader.copyTo;
import static com.hiki.isup.sdk.NativeSdkLoader.readString;

/**
 * 设备注册回调（NET_ECMS_StartListen 传入）。
 *
 * <p>处理 ISUP 5.0 的完整注册链路：</p>
 * <ol>
 *   <li>ENUM_DEV_AUTH：回传 EHomeKey（接入密钥）</li>
 *   <li>ENUM_DEV_SESSIONKEY：把设备 SessionKey 回灌给 SDK</li>
 *   <li>ENUM_DEV_DAS_REQ：回传 DAS 重定向地址（必须是公网 IP）</li>
 *   <li>ENUM_DEV_ON：设备上线，登记到 DeviceRegistry</li>
 *   <li>ENUM_DEV_OFF：设备下线，清理设备与其全部会话</li>
 * </ol>
 *
 * <p>注意：回调由 native 线程调用，绝不能向外抛异常。</p>
 */
@Component
public class DeviceRegisterCallback implements HCISUPCMS.DEVICE_REGISTER_CB {

    private static final Logger log = LoggerFactory.getLogger(DeviceRegisterCallback.class);

    /** 图片服务器端口（抓图上报用），与官方 Demo 保持一致 */
    private static final int PICTURE_SERVER_PORT = 6011;

    /** 重注册风暴判定：60 秒滑动窗口内重注册达到这个次数就打 WARN 提示 */
    private static final int STORM_THRESHOLD = 20;
    private static final long STORM_WINDOW_MS = 60_000L;

    private final AtomicInteger reRegisterCount = new AtomicInteger();
    private volatile long reRegisterWindowStart = System.currentTimeMillis();

    private final IsupProperties properties;
    private final DeviceRegistry deviceRegistry;
    private final SessionManager sessionManager;
    private final NativeSdkLoader sdkLoader;

    public DeviceRegisterCallback(IsupProperties properties,
                                  DeviceRegistry deviceRegistry,
                                  SessionManager sessionManager,
                                  NativeSdkLoader sdkLoader) {
        this.properties = properties;
        this.deviceRegistry = deviceRegistry;
        this.sessionManager = sessionManager;
        this.sdkLoader = sdkLoader;
    }

    @Override
    public boolean invoke(int lUserID, int dwDataType, Pointer pOutBuffer,
                          int dwOutLen, Pointer pInBuffer, int dwInLen, Pointer pUser) {
        try {
            switch (dwDataType) {
                case HCISUPCMS.EHOME_REGISTER_TYPE.ENUM_DEV_ON -> handleOnline(lUserID, pOutBuffer, pInBuffer);
                case HCISUPCMS.EHOME_REGISTER_TYPE.ENUM_DEV_AUTH -> handleAuth(pOutBuffer, pInBuffer);
                case HCISUPCMS.EHOME_REGISTER_TYPE.ENUM_DEV_SESSIONKEY -> handleSessionKey(pOutBuffer);
                case HCISUPCMS.EHOME_REGISTER_TYPE.ENUM_DEV_DAS_REQ -> handleDasRequest(pInBuffer);
                case HCISUPCMS.EHOME_REGISTER_TYPE.ENUM_DEV_OFF -> handleOffline(lUserID);
                case HCISUPCMS.EHOME_REGISTER_TYPE.ENUM_DEV_ADDRESS_CHANGED ->
                        log.info("设备地址发生变化，lUserID={}", lUserID);
                case HCISUPCMS.EHOME_REGISTER_TYPE.ENUM_DEV_DAS_REREGISTER -> {
                    int n = countInWindow();
                    log.info("设备重注册，lUserID={}（近 60s 第 {} 次）", lUserID, n);
                    if (n == STORM_THRESHOLD) {
                        log.warn("设备重注册风暴：60 秒内已 {} 次。这类高频抖动基本都是设备侧问题——"
                                + "①设备「平台接入」里同时启用了中心1/中心2（两条注册互踢）；"
                                + "②设备固件不支持 ISUP 告警主机 ISAPI。服务端改不了，请到设备网页只保留一个中心", n);
                    }
                }
                case HCISUPCMS.EHOME_REGISTER_TYPE.ENUM_DEV_DAS_PINGREO ->
                        log.debug("设备注册心跳，lUserID={}", lUserID);
                case HCISUPCMS.EHOME_REGISTER_TYPE.ENUM_DEV_DAS_EHOMEKEY_ERROR ->
                        log.error("ISUP 密钥校验失败，请检查 isup.ehome-key 与录像机配置是否一致，lUserID={}", lUserID);
                case HCISUPCMS.EHOME_REGISTER_TYPE.ENUM_DEV_SESSIONKEY_ERROR ->
                        // SDK 在 SetHost 时 ISAPI 透传 GET /ISAPI/Event/notification/httpHosts 超时
                        // （SDK 日志 error[10]），设备不支持该接口 → 被踢下线重注册。
                        // SetHost 是 SDK 每次上线的固定动作，isup.server-info-enabled=false
                        // 无法跳过，只能由设备侧支持该 ISAPI 或改用 ISUP 4.0 协议根治。
                        // 用 WARN 不用 ERROR：设备在线、预览/回放都正常，这只是 SDK 周期性下发
                        // 告警主机失败留下的噪音，服务端改不掉，标 ERROR 会让人误以为服务挂了。
                        log.warn("设备不响应 ISAPI 告警主机查询（GET /ISAPI/Event/notification/httpHosts），"
                                + "SDK 会周期性重连它一次；设备在线和取流不受影响。lUserID={}", lUserID);
                case HCISUPCMS.EHOME_REGISTER_TYPE.ENUM_DEV_SESSIONKEY_REQ ->
                        log.debug("设备 SessionKey 请求，lUserID={}", lUserID);
                case HCISUPCMS.EHOME_REGISTER_TYPE.ENUM_DEV_SLEEP ->
                        log.info("设备进入休眠状态，lUserID={}", lUserID);
                default -> log.warn("未知的注册回调类型：dwDataType={}, lUserID={}", dwDataType, lUserID);
            }
        } catch (Exception e) {
            log.error("处理设备注册回调异常：dwDataType={}, lUserID={}", dwDataType, lUserID, e);
        }
        return true;
    }

    // ==================== 设备上线 ====================

    private void handleOnline(int lUserID, Pointer pOutBuffer, Pointer pInBuffer) {
        HCISUPCMS.NET_EHOME_DEV_REG_INFO_V12 regInfo = readRegInfo(pOutBuffer);
        String deviceId = readString(regInfo.struRegInfo.byDeviceID);
        String deviceIp = readString(regInfo.struRegInfo.struDevAdd.szIP);
        String serial = readString(regInfo.struRegInfo.sDeviceSerial);
        String devName = readString(regInfo.sDevName);

        deviceRegistry.online(lUserID, deviceId, deviceIp, serial, devName);
        log.info("设备上线：deviceId={}, lUserID={}, ip={}, serial={}, name={}",
                deviceId, lUserID, deviceIp, serial, devName);

        // 注册保活参数始终回传；总开关仅控制可选的告警、图片服务器信息。
        String publicIp = properties.getPublicIp();
        int cmsPort = properties.getCms().getListenPort();
        boolean alarmEnabled = properties.isServerInfoEnabled() && properties.isAlarmServerEnabled();
        boolean pictureEnabled = properties.isServerInfoEnabled() && properties.isPictureServerEnabled();
        HCISUPCMS.NET_EHOME_SERVER_INFO_V50 serverInfo = new HCISUPCMS.NET_EHOME_SERVER_INFO_V50();
        serverInfo.dwSize = serverInfo.size();

        // 与 CMS 监听端使用相同的心跳周期和超时次数。
        serverInfo.dwKeepAliveSec = properties.getKeepAliveSec();
        serverInfo.dwTimeOutCount = properties.getTimeOutCount();
        serverInfo.dwAlarmKeepAliveSec = properties.getKeepAliveSec();
        serverInfo.dwAlarmTimeOutCount = properties.getTimeOutCount();

        if (alarmEnabled) {
            copyTo(serverInfo.struUDPAlarmSever.szIP, publicIp);
            serverInfo.struUDPAlarmSever.wPort = (short) cmsPort;
            copyTo(serverInfo.struTCPAlarmSever.szIP, publicIp);
            serverInfo.struTCPAlarmSever.wPort = (short) cmsPort;
            serverInfo.dwAlarmServerType = 1;   // 支持 UDP、TCP 两种上报
        }

        if (pictureEnabled) {
            copyTo(serverInfo.struPictureSever.szIP, publicIp);
            serverInfo.struPictureSever.wPort = (short) PICTURE_SERVER_PORT;
            serverInfo.dwPicServerType = 4;     // ISUP5.0 图片服务器
        }

        serverInfo.write();
        int len = serverInfo.size();
        pInBuffer.write(0, serverInfo.getPointer().getByteArray(0, len), 0, len);
        log.info("已回传服务器信息：deviceId={}, size={}, keepAliveSec={}, timeOutCount={}, alarmEnabled={}, pictureEnabled={}",
                deviceId, len, serverInfo.dwKeepAliveSec, serverInfo.dwTimeOutCount, alarmEnabled, pictureEnabled);
    }

    // ==================== ISUP5.0 认证 ====================

    private void handleAuth(Pointer pOutBuffer, Pointer pInBuffer) {
        HCISUPCMS.NET_EHOME_DEV_REG_INFO_V12 regInfo = readRegInfo(pOutBuffer);
        String deviceId = readString(regInfo.struRegInfo.byDeviceID);
        String key = properties.getEhomeKey();
        if (key == null || key.isBlank()) {
            log.error("ISUP5.0 认证回调：未配置 isup.ehome-key，设备 {} 无法通过认证", deviceId);
            return;
        }
        byte[] bytes = key.getBytes(StandardCharsets.UTF_8);
        pInBuffer.write(0, bytes, 0, bytes.length);
        log.info("ISUP5.0 认证回调：deviceId={}, 已回传 EHomeKey", deviceId);
    }

    // ==================== SessionKey ====================

    private void handleSessionKey(Pointer pOutBuffer) {
        HCISUPCMS.NET_EHOME_DEV_REG_INFO_V12 regInfo = readRegInfo(pOutBuffer);
        String deviceId = readString(regInfo.struRegInfo.byDeviceID);

        HCISUPCMS.NET_EHOME_DEV_SESSIONKEY sessionKey = new HCISUPCMS.NET_EHOME_DEV_SESSIONKEY();
        System.arraycopy(regInfo.struRegInfo.byDeviceID, 0, sessionKey.sDeviceID, 0,
                Math.min(sessionKey.sDeviceID.length, regInfo.struRegInfo.byDeviceID.length));
        System.arraycopy(regInfo.struRegInfo.bySessionKey, 0, sessionKey.sSessionKey, 0,
                Math.min(sessionKey.sSessionKey.length, regInfo.struRegInfo.bySessionKey.length));
        sessionKey.write();

        boolean result = sdkLoader.cms().NET_ECMS_SetDeviceSessionKey(sessionKey.getPointer());
        if (!result) {
            log.error("NET_ECMS_SetDeviceSessionKey 失败：deviceId={}, errorCode={}",
                    deviceId, sdkLoader.cms().NET_ECMS_GetLastError());
        } else {
            log.info("ISUP5.0 SessionKey 设置成功：deviceId={}", deviceId);
        }
    }

    // ==================== DAS 重定向 ====================

    private void handleDasRequest(Pointer pInBuffer) {
        String publicIp = properties.getPublicIp();
        int cmsPort = properties.getCms().getListenPort();
        String dasInfo = "{\n"
                + "    \"Type\":\"DAS\",\n"
                + "    \"DasInfo\": {\n"
                + "        \"Address\":\"" + publicIp + "\",\n"
                + "        \"Domain\":\"\",\n"
                + "        \"ServerID\":\"\",\n"
                + "        \"Port\":" + cmsPort + ",\n"
                + "        \"UdpPort\":" + cmsPort + "\n"
                + "    }\n"
                + "}";
        byte[] bytes = dasInfo.getBytes(StandardCharsets.UTF_8);
        pInBuffer.write(0, bytes, 0, bytes.length);
        log.info("ISUP5.0 DAS 重定向回调，已回传：address={}, port={}", publicIp, cmsPort);
    }

    // ==================== 设备下线 ====================

    private void handleOffline(int lUserID) {
        // 掉线先进宽限期（DeviceRegistry 里配的 isup.device-offline-grace-sec），
        // 抖动时设备大概率几秒内就重连，等宽限期过了还没回来才停它的会话，
        // 否则上下线抖一次预览就断一次。回调在 SDK 线程里执行，停止动作本身是异步的。
        deviceRegistry.offlineConnection(lUserID, sessionManager::stopByConnectionAsync);
    }

    /** 60 秒滑动窗口内的重注册计数（回调在 native 线程里，用原子量足够） */
    private int countInWindow() {
        long now = System.currentTimeMillis();
        if (now - reRegisterWindowStart >= STORM_WINDOW_MS) {
            reRegisterWindowStart = now;
            reRegisterCount.set(0);
        }
        return reRegisterCount.incrementAndGet();
    }

    private HCISUPCMS.NET_EHOME_DEV_REG_INFO_V12 readRegInfo(Pointer pOutBuffer) {
        HCISUPCMS.NET_EHOME_DEV_REG_INFO_V12 regInfo = new HCISUPCMS.NET_EHOME_DEV_REG_INFO_V12();
        regInfo.write();
        Pointer pRegInfo = regInfo.getPointer();
        pRegInfo.write(0, pOutBuffer.getByteArray(0, regInfo.size()), 0, regInfo.size());
        regInfo.read();
        return regInfo;
    }
}
