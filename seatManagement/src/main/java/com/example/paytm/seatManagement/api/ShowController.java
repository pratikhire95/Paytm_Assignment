package com.example.paytm.seatManagement.api;

import com.example.paytm.seatManagement.config.AppSettings;
import com.example.paytm.seatManagement.domain.ShowInfo;
import com.example.paytm.seatManagement.domain.ShowRepository;
import com.example.paytm.seatManagement.domain.ShowState;
import com.example.paytm.seatManagement.observability.ObservabilityFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Create a show (admin) and read its state (public, read-only). */
@RestController
public class ShowController {

    private static final Logger log = LoggerFactory.getLogger(ShowController.class);

    private final ShowRepository shows;
    private final AppSettings settings;

    public ShowController(ShowRepository shows, AppSettings settings) {
        this.shows = shows;
        this.settings = settings;
    }

    /** Admin only (enforced centrally in AuthFilter). */
    @PostMapping("/shows")
    public ResponseEntity<String> create(@RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest req) {
        if (body == null) {
            throw RequestValidator.bad("A JSON body is required");
        }
        String name = RequestValidator.showName(body.get("name"));
        List<String> seats = RequestValidator.seatList(body.get("seats"), settings.maxSeatsPerShow());
        long price = RequestValidator.priceMinorUnits(body.get("price_paise"));
        int limit = RequestValidator.perUserLimit(body.get("per_user_limit"), settings.defaultPerUserLimit());

        ShowInfo show = shows.create(name, price, limit, seats);
        req.setAttribute(ObservabilityFilter.ATTR_OUTCOME, "show_created");
        log.info("show created show_id={} seats={} price_paise={} per_user_limit={}", show.id(), seats.size(), price,
                limit);
        return Http.json(201, Views.show(Views.freshState(show, seats)));
    }

    /**
     * Per-seat status and counts, from one consistent snapshot. {@code ?seats=false} returns counts only (cheap,
     * meant for polling during a burst).
     */
    @GetMapping("/shows/{id}")
    public ResponseEntity<String> get(@PathVariable("id") String id,
            @RequestParam(name = "seats", required = false) String seatsParam) {
        UUID showId = RequestValidator.uuidOrNotFound(id, "show_not_found", "No such show");
        boolean includeSeats = seatsParam == null || !isFalse(seatsParam);
        ShowState state = shows.state(showId, includeSeats);
        return Http.json(200, Views.show(state));
    }

    private static boolean isFalse(String v) {
        return "false".equalsIgnoreCase(v) || "0".equals(v) || "no".equalsIgnoreCase(v);
    }
}
