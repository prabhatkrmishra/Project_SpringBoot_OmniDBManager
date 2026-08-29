package com.pkmprojects.mongodbserver.controller;

import com.pkmprojects.mongodbserver.service.MonitorService;
import com.pkmprojects.mongodbserver.service.MysqlMonitorService;
import com.pkmprojects.mongodbserver.service.PostgresMonitorService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Live monitoring page (any authenticated user). The page subscribes to a
 * Server-Sent Events stream that pushes a {@link MonitorSnapshot} every two
 * seconds. A small daemon scheduler feeds the stream; a client disconnect
 * stops its ticks. Threads are daemon so they never keep the JVM alive.
 */
@Controller
public class MonitorController {

    private static final Logger log = LoggerFactory.getLogger(MonitorController.class);

    private static final long TICK_MILLIS = 2000;

    private final MonitorService monitorService;
    private final Optional<PostgresMonitorService> postgresMonitorService;
    private final Optional<MysqlMonitorService> mysqlMonitorService;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService tickExecutor;

    public MonitorController(@Autowired(required = false) MonitorService monitorService,
                             @Autowired(required = false) PostgresMonitorService postgresMonitorService,
                             @Autowired(required = false) MysqlMonitorService mysqlMonitorService) {
        this.monitorService = monitorService;
        this.postgresMonitorService = Optional.ofNullable(postgresMonitorService);
        this.mysqlMonitorService = Optional.ofNullable(mysqlMonitorService);
        this.scheduler = Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "monitor-sse");
            thread.setDaemon(true);
            return thread;
        });
        this.tickExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @GetMapping("/monitor")
    public String monitor(@RequestParam(name = "engine", required = false) String engine, Model model) {
        String eng = engine != null ? engine.toLowerCase() : "mongo";
        if (eng.equals("postgres") && postgresMonitorService.isEmpty()) eng = "mongo";
        if (eng.equals("mysql") && mysqlMonitorService.isEmpty()) eng = "mongo";
        if (!eng.equals("postgres") && !eng.equals("mysql")) eng = "mongo";
        model.addAttribute("monitorEngine", eng);
        model.addAttribute("postgresAvailable", postgresMonitorService.isPresent());
        model.addAttribute("mysqlAvailable", mysqlMonitorService.isPresent());
        return "monitor";
    }

    @GetMapping("/monitor/stream")
    public ResponseEntity<SseEmitter> stream(@RequestParam(name = "engine", required = false) String engine) {
        String eng = engine != null ? engine.toLowerCase() : "mongo";
        if (eng.equals("postgres") && postgresMonitorService.isEmpty()) eng = "mongo";
        if (eng.equals("mysql") && mysqlMonitorService.isEmpty()) eng = "mongo";
        if (!eng.equals("postgres") && !eng.equals("mysql")) eng = "mongo";
        String finalEng = eng;
        SseEmitter emitter = new SseEmitter(60_000L);
        // Heartbeat comment every 15s keeps proxies from buffering/closing idle SSE.
        ScheduledFuture<?> heartbeat = scheduler.scheduleWithFixedDelay(() -> {
            try {
                emitter.send(SseEmitter.event().comment("keepalive"));
            } catch (IOException ignored) {
                // Client gone — onCompletion will cancel tick future.
            }
        }, 15, 15, TimeUnit.SECONDS);
        ScheduledFuture<?> future = scheduler.scheduleWithFixedDelay(() -> sendTick(emitter, finalEng),
                0, TICK_MILLIS, TimeUnit.MILLISECONDS);
        emitter.onCompletion(() -> {
            future.cancel(true);
            heartbeat.cancel(true);
        });
        emitter.onTimeout(() -> {
            future.cancel(true);
            heartbeat.cancel(true);
            emitter.complete();
        });
        emitter.onError(e -> {
            future.cancel(true);
            heartbeat.cancel(true);
        });
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .header("X-Accel-Buffering", "no")
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(emitter);
    }

    private void sendTick(SseEmitter emitter, String engine) {
        try {
            // Offload blocking JDBC snapshot to virtual threads with hard timeout
            // so scheduler threads never block on a hung PG/MySQL.
            String data = CompletableFuture.supplyAsync(() -> {
                if ("postgres".equals(engine)) {
                    var snapshot = postgresMonitorService.get().getSnapshot();
                    return postgresMonitorService.get().serialize(snapshot);
                } else if ("mysql".equals(engine)) {
                    var snapshot = mysqlMonitorService.get().getSnapshot();
                    return mysqlMonitorService.get().serialize(snapshot);
                } else {
                    if (monitorService == null) {
                        throw new IllegalStateException("Mongo monitoring is not available");
                    }
                    var snapshot = monitorService.getSnapshot();
                    return monitorService.serialize(snapshot);
                }
            }, tickExecutor).orTimeout(3, TimeUnit.SECONDS).join();
            emitter.send(SseEmitter.event().name("tick").data(data));
        } catch (java.util.concurrent.CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof TimeoutException) {
                log.warn("Monitor snapshot tick timed out for engine {}", engine);
                try {
                    emitter.send(SseEmitter.event().name("tick").data("{\"error\":\"snapshot timeout\"}"));
                } catch (IOException ignored) {
                    log.debug("Could not send timeout tick; client gone", ignored);
                    throw new SseStreamClosed(new IOException("timeout tick send failed", ignored));
                }
                return;
            }
            if (cause instanceof RuntimeException re) throw re;
            if (cause instanceof IOException ioe) {
                log.debug("Monitor SSE client disconnected", ioe);
                throw new SseStreamClosed(ioe);
            }
            log.warn("Monitor snapshot tick failed", cause != null ? cause : e);
            try {
                emitter.completeWithError(cause != null ? cause : e);
            } catch (RuntimeException ignored) {
                log.debug("Could not signal monitor SSE error; response already unusable", ignored);
            }
            throw new RuntimeException(cause != null ? cause : e);
        } catch (IOException e) {
            log.debug("Monitor SSE client disconnected", e);
            throw new SseStreamClosed(e);
        } catch (RuntimeException e) {
            log.warn("Monitor snapshot tick failed", e);
            try {
                emitter.completeWithError(e);
            } catch (RuntimeException ignored) {
                log.debug("Could not signal monitor SSE error; response already unusable", ignored);
            }
            throw e;
        }
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdown();
        tickExecutor.shutdown();
    }

    private static class SseStreamClosed extends RuntimeException {
        SseStreamClosed(IOException cause) {
            super(cause);
        }
    }
}