package com.hiki.isup.callback;

import com.hiki.isup.sdk.HCISUPCMS;
import com.hiki.isup.service.DeviceRegistry;
import com.hiki.isup.service.SessionManager;
import com.sun.jna.Pointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** CMS exception notification is failure evidence, never proof of native Stop. */
@Component
public final class CmsExceptionCallback implements HCISUPCMS.EXCEPTION_CALLBACK {
    private static final Logger log = LoggerFactory.getLogger(CmsExceptionCallback.class);
    private final SessionManager sessions;
    private final DeviceRegistry devices;
    public CmsExceptionCallback(SessionManager sessions, DeviceRegistry devices) {
        this.sessions = sessions;
        this.devices = devices;
    }
    @Override public void invoke(int type, int userId, int nativeSessionId, Pointer user) {
        try {
            if (type == HCISUPCMS.EHOME_PLAYBACK_EXCEPTION && devices != null)
                sessions.onPlaybackException(userId, nativeSessionId, devices);
        } catch (Throwable failure) {
            log.error("CMS exception callback failed: userId={}, nativeSessionId={}", userId, nativeSessionId, failure);
        }
    }
}
