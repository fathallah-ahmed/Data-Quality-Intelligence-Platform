package wevioo.backend.controller;

import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import wevioo.backend.service.EventStreamService;

@RestController
@RequestMapping("/api/events")
public class EventController {
    private final EventStreamService eventStreamService;

    public EventController(EventStreamService eventStreamService) {
        this.eventStreamService = eventStreamService;
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(Authentication authentication) {
        return eventStreamService.subscribe(authentication.getName());
    }
}
