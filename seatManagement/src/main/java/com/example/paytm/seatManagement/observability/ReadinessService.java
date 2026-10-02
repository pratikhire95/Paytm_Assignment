package com.example.paytm.seatManagement.observability;

import com.example.paytm.seatManagement.config.AppSettings;
import com.example.paytm.seatManagement.config.Pools;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.stereotype.Component;

/**
 * Readiness = "can this instance serve traffic right now?". It actually talks to the database and fails
 * closed: no connection, no SELECT 1 result, or the JVM is shutting down => not ready.
 *
 * <p>Liveness ({@code /healthz}) is intentionally dumber: it only proves the process is up and answering,
 * so a database outage does not make the platform restart a perfectly healthy JVM in a loop.
 */
@Component
public class ReadinessService implements ApplicationListener<ContextClosedEvent>, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ReadinessService.class);

    private final HikariDataSource probePool;
    private volatile boolean shuttingDown = false;
    private volatile boolean lastDbOk = true;

    public ReadinessService(AppSettings settings) {
        this.probePool = Pools.probePool(settings);
    }

    /** @return true only if a real round trip to the database succeeded just now. */
    public boolean databaseReachable() {
        try (Connection c = probePool.getConnection();
                Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("SELECT 1")) {
            boolean ok = rs.next() && rs.getInt(1) == 1;
            noteState(ok);
            return ok;
        } catch (SQLException | RuntimeException e) {
            noteState(false);
            return false;
        }
    }

    public boolean isShuttingDown() {
        return shuttingDown;
    }

    /** Logs only on transitions, so a flapping dependency is visible without flooding the log. */
    private void noteState(boolean ok) {
        if (ok != lastDbOk) {
            lastDbOk = ok;
            if (ok) {
                log.info("readiness: database reachable again");
            } else {
                log.warn("readiness: database NOT reachable");
            }
        }
    }

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        shuttingDown = true; // /readyz flips to 503 immediately so the router stops sending new requests
    }

    @Override
    public void destroy() {
        probePool.close();
    }
}
