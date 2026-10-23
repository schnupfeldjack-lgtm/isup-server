package com.hiki.isup.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FairSchedulingEnvironmentTest {
    @Test void exactDeploymentEnvironmentNameActivatesFairScheduling() {
        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("systemEnvironment",
                Map.of("ISUP_WAYBILL_VIDEO_FAIR_SCHEDULING_ENABLED", "true")));
        IsupProperties properties = Binder.get(environment).bind("isup", Bindable.of(IsupProperties.class)).orElseGet(IsupProperties::new);
        assertTrue(properties.getWaybillVideo().isFairSchedulingEnabled());
    }
}
