package com.example.paytm.seatManagement.observability;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.Locale;
import javax.sql.DataSource;

/**
 * JVM and connection-pool gauges: the "is it saturated?" signals you look at during a burst.
 * The pool numbers are the most important ones: {@code db_pool_threads_awaiting} &gt; 0 means requests are
 * queueing for a database connection (expected during a stampede, a problem if it never drains).
 */
public final class RuntimeMetrics {

    private RuntimeMetrics() {
    }

    public static String render(DataSource dataSource) {
        StringBuilder sb = new StringBuilder(2048);

        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = mem.getHeapMemoryUsage();
        MemoryUsage nonHeap = mem.getNonHeapMemoryUsage();
        PromText.header(sb, "jvm_memory_used_bytes", "gauge", "JVM memory in use.");
        PromText.sample(sb, "jvm_memory_used_bytes", PromText.label("area", "heap"), heap.getUsed());
        PromText.sample(sb, "jvm_memory_used_bytes", PromText.label("area", "nonheap"), nonHeap.getUsed());
        PromText.header(sb, "jvm_memory_max_bytes", "gauge", "JVM max heap (-1 = undefined).");
        PromText.sample(sb, "jvm_memory_max_bytes", PromText.label("area", "heap"), heap.getMax());

        PromText.header(sb, "jvm_threads_live", "gauge", "Live JVM threads.");
        PromText.sample(sb, "jvm_threads_live", "", ManagementFactory.getThreadMXBean().getThreadCount());

        PromText.header(sb, "jvm_gc_collections_total", "counter", "Garbage collections by collector.");
        PromText.header(sb, "jvm_gc_pause_seconds_total", "counter", "Accumulated GC time by collector.");
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            String l = PromText.label("gc", gc.getName());
            PromText.sample(sb, "jvm_gc_collections_total", l, gc.getCollectionCount());
            PromText.sample(sb, "jvm_gc_pause_seconds_total", l,
                    String.format(Locale.ROOT, "%.3f", gc.getCollectionTime() / 1000.0));
        }

        PromText.header(sb, "process_uptime_seconds", "gauge", "Process uptime.");
        PromText.sample(sb, "process_uptime_seconds", "",
                String.format(Locale.ROOT, "%.1f", ManagementFactory.getRuntimeMXBean().getUptime() / 1000.0));
        PromText.header(sb, "process_cpus", "gauge", "CPUs visible to the JVM (container limits applied).");
        PromText.sample(sb, "process_cpus", "", Runtime.getRuntime().availableProcessors());

        if (dataSource instanceof HikariDataSource) {
            HikariPoolMXBean pool = ((HikariDataSource) dataSource).getHikariPoolMXBean();
            if (pool != null) {
                PromText.header(sb, "db_pool_connections", "gauge", "Database pool connections by state.");
                PromText.sample(sb, "db_pool_connections", PromText.label("state", "active"),
                        pool.getActiveConnections());
                PromText.sample(sb, "db_pool_connections", PromText.label("state", "idle"),
                        pool.getIdleConnections());
                PromText.sample(sb, "db_pool_connections", PromText.label("state", "total"),
                        pool.getTotalConnections());
                PromText.header(sb, "db_pool_threads_awaiting", "gauge",
                        "Requests currently queued waiting for a database connection.");
                PromText.sample(sb, "db_pool_threads_awaiting", "", pool.getThreadsAwaitingConnection());
            }
        }
        return sb.toString();
    }
}
