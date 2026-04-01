package uk.jtoye.core.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class OrderSseService {
    private static final Logger log = LoggerFactory.getLogger(OrderSseService.class);
    private static final long SSE_TIMEOUT = 300_000L; // 5 minutes

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        log.debug("SSE client subscribed, total: {}", emitters.size());
        return emitter;
    }

    public void broadcast(OrderStateChangeEvent event) {
        log.debug("Broadcasting order state change to {} SSE clients", emitters.size());
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name("order-state-change")
                        .data(event));
            } catch (IOException e) {
                emitters.remove(emitter);
            }
        }
    }
}
