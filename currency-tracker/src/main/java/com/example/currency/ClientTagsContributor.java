package com.example.currency;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import org.springframework.http.server.observation.DefaultServerRequestObservationConvention;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.stereotype.Component;

@Component
public class ClientTagsContributor extends DefaultServerRequestObservationConvention {

    @Override
    public KeyValues getLowCardinalityKeyValues(ServerRequestObservationContext context) {
        KeyValues defaultValues = super.getLowCardinalityKeyValues(context);

        Object client = context.getCarrier().getAttribute(ClientTagFilter.CLIENT_ATTR);
        Object user = context.getCarrier().getAttribute(ClientTagFilter.USER_ATTR);

        return defaultValues.and(
                KeyValue.of("client", client != null ? client.toString() : "unknown"),
                KeyValue.of("user", user != null ? user.toString() : "unknown")
        );
    }
}
