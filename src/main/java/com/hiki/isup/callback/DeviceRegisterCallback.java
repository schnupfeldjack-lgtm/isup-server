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
                case HCISUPCMS.EHOME_REGISTER_TYPE.ENUM_DEV_DAS_REREGISTER ->
                        log.info("设备重注册，lUserID={}", lUserID);
                case HCISUPCMS.EHOME_REGISTER_TYPE.ENUM_DEV_DAS_PINGREO ->
                        log.debug("设备注册心跳，lUserID={}", lUserID);
                case HCISUPCMS.EHOME_REGISTER_TYPE.ENUM_DEV_DAS_EHOMEKEY_ERROR ->
                        log.error("ISUP 密钥校验失败，请检查 isup.ehome-key 与录像机配置是否一致，lUserID={}", lUserID);
                case HCISUPCMS.EHOME_REGISTER_TYPE.ENUM_DEV_SESSIONKEY_ERROR ->
                        log.error("SessionKey 交互异常，lUserID={}", lUserID);
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

        // 回传给设备的服务器信息（必须是公网地址）
        String publicIp = properties.getPublicIp();
        int cmsPort = properties.getCms().getListenPort();
        HCISUPCMS.NET_EHOME_SERVER_INFO_V50 serverInfo = new HCISUPCMS.NET_EHOME_SERVER_INFO_V50();
        serverInfo.read();
        serverInfo.dwSize = serverInfo.size();

        copyTo(serverInfo.struUDPAlarmSever.szIP, publicIp);
        serverInfo.struUDPAlarmSever.wPort = (short) cmsPort;
        copyTo(serverInfo.struTCPAlarmSever.szIP, publicIp);
        serverInfo.struTCPAlarmSever.wPort = (short) cmsPort;
        serverInfo.dwAlarmServerType = 1;   // 支持 UDP、TCP 两种上报

        copyTo(serverInfo.struPictureSever.szIP, publicIp);
        serverInfo.struPictureSever.wPort = (short) 6011;
        serverInfo.dwPicServerType = 4;     // ISUP5.0 图片服务器

        serverInfo.write();
        int len = serverInfo.size();
        pInBuffer.write(0, serverInfo.getPointer().getByteArray(0, len), 0, len);
        log.info("已回传服务器信息给设备：deviceId={}, publicIp={}, cmsPort={}", deviceId, publicIp, cmsPort);
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
        String deviceId = deviceRegistry.offline(lUserID);
        if (deviceId != null) {
            // 当前处于 SDK 注册回调线程，异步停止，避免在回调内重入 SDK
            sessionManager.stopByDeviceAsync(deviceId);
        }
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
