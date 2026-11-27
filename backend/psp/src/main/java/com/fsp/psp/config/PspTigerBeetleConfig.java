package com.fsp.psp.config;

import com.tigerbeetle.Client;
import com.tigerbeetle.UInt128;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;

/**
 * The ONLY place allowed to import com.tigerbeetle.* besides PspTbService.
 * tigerbeetle-java 0.17.9: Client takes a 16-byte cluster id plus the replica addresses.
 */
@Configuration
public class PspTigerBeetleConfig {

    private static final int CLUSTER_ID_BYTES = 16;

    @Bean(destroyMethod = "close")
    public Client pspTbClient(PspProperties props) {
        var tb = props.getTigerbeetle();
        byte[] clusterId = UInt128.asBytes(tb.getClusterId());
        if (clusterId.length != CLUSTER_ID_BYTES) {
            throw new IllegalStateException("cluster id encoding failed"); // impossible; belt & braces
        }
        String[] addresses = Arrays.stream(tb.getAddresses().split(","))
            .map(String::trim).filter(s -> !s.isEmpty()).toArray(String[]::new);
        return new Client(clusterId, addresses);
    }
}
