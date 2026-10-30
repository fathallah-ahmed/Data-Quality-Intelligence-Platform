package wevioo.backend.service;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import wevioo.backend.dto.LiveEvent;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class EventStreamService {
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public SseEmitter subscribe(String username) {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(_error -> emitters.remove(emitter));
        sendToEmitter(emitter, LiveEvent.connected(username));
        return emitter;
    }

    public void publish(LiveEvent event) {
        for (SseEmitter emitter : emitters) {
            sendToEmitter(emitter, event);
        }
    }

    private void sendToEmitter(SseEmitter emitter, LiveEvent event) {
        try {
            emitter.send(SseEmitter.event()
                .name(event.type())
                .id(String.valueOf(System.currentTimeMillis()))
                .data(event));
        } catch (IOException | IllegalStateException e) {
            emitters.remove(emitter);
        }
    }
}
