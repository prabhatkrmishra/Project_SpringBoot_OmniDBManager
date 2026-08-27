package com.pkmprojects.mongodbserver.controller;

import com.pkmprojects.mongodbserver.service.MonitorService;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

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
    private final ScheduledExecutorService scheduler;

    public MonitorController(MonitorService monitorService,
                             @Autowired(required = false) PostgresMonitorService postgresMonitorService) {
        this.monitorService = monitorService;
        this.postgresMonitorService = Optional.ofNullable(postgresMonitorService);
        this.scheduler = Executors.newScheduledThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "monitor-sse");
            thread.setDaemon(true);
            return thread;
        });
    }

    @GetMapping("/monitor")
    public String monitor(@RequestParam(name = "engine", required = false) String engine, Model model) {
        String eng = engine != null ? engine.toLowerCase() : "mongo";
        if (!eng.equals("postgres")) eng = "mongo";
        model.addAttribute("monitorEngine", eng);
        model.addAttribute("postgresAvailable", postgresMonitorService.isPresent());
        return "monitor";
    }

    @GetMapping("/monitor/stream")
    public ResponseEntity<SseEmitter> stream(@RequestParam(name = "engine", required = false) String engine) {
        String eng = engine != null ? engine.toLowerCase() : "mongo";
        boolean pg = eng.equals("postgres") && postgresMonitorService.isPresent();
        SseEmitter emitter = new SseEmitter(0L);
        ScheduledFuture<?> future = scheduler.scheduleWithFixedDelay(() -> sendTick(emitter, pg),
                0, TICK_MILLIS, TimeUnit.MILLISECONDS);
        emitter.onCompletion(() -> future.cancel(true));
        emitter.onTimeout(() -> future.cancel(true));
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .header("X-Accel-Buffering", "no")
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(emitter);
    }

    private void sendTick(SseEmitter emitter, boolean postgres) {
        try {
            String data;
            if (postgres) {
                var snapshot = postgresMonitorService.get().getSnapshot();
                data = postgresMonitorService.get().serialize(snapshot);
            } else {
                var snapshot = monitorService.getSnapshot();
                data = monitorService.serialize(snapshot);
            }
            emitter.send(SseEmitter.event().name("tick").data(data));
        } catch (IOException e) {
            // Client went away. The underlying response is already unusable, so
            // do not complete() it (that throws AsyncRequestNotUsableException).
            // Re-throwing ends the scheduled task so it does not keep ticking
            // into the void; onCompletion cancels the future.
            log.debug("Monitor SSE client disconnected", e);
            throw new SseStreamClosed(e);
        } catch (RuntimeException e) {
            // A snapshot failed. Best-effort signal the client, then stop the
            // loop. If the response is already unusable (client gone), the
            // signal is skipped rather than letting it cascade.
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
    }

    private static class SseStreamClosed extends RuntimeException {
        SseStreamClosed(IOException cause) {
            super(cause);
        }
    }
}