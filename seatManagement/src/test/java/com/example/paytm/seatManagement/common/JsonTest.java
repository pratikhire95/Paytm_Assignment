package com.example.paytm.seatManagement.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.paytm.seatManagement.support.MiniJson;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonTest {

    @Test
    void escapesEverythingThatMustBeEscaped() {
        String nasty = "quote\" backslash\\ newline\n return\r tab\t ctrl" + (char) 1 + " sep" + (char) 0x2028 + " end";
        String json = Json.quote(nasty);
        assertTrue(json.indexOf('\n') < 0 && json.indexOf('\r') < 0 && json.indexOf('\u0001') < 0);
        assertEquals(nasty, MiniJson.parse(json));
    }

    @Test
    void nullAndUnicodeRoundTrip() {
        assertEquals("null", Json.quote(null));
        // Devanagari letters, a BMP symbol and an astral-plane (surrogate pair) code point, built without source-file escapes.
        String s = new String(new int[] {0x092B, 0x094D, 0x0930, 0x2605, 0x1F3AB}, 0, 5);
        assertEquals(s, MiniJson.parse(Json.quote(s)));
    }

    @Test
    void objectBuilderProducesValidJson() {
        String json = new Json.Obj()
                .str("s", "a\"b")
                .num("n", 25000L)
                .bool("b", true)
                .strings("l", Arrays.asList("A1", "B2"))
                .raw("r", "{\"x\":1}")
                .value("v", Arrays.asList(1, "two", null))
                .build();
        Map<String, Object> m = MiniJson.object(json);
        assertEquals("a\"b", m.get("s"));
        assertEquals(25000L, m.get("n"));
        assertEquals(Boolean.TRUE, m.get("b"));
        assertEquals(Arrays.asList("A1", "B2"), m.get("l"));
        assertEquals(1L, ((Map<?, ?>) m.get("r")).get("x"));
        List<?> v = (List<?>) m.get("v");
        assertEquals(3, v.size());
    }

    @Test
    void errorEnvelopeIsValidJsonAndCarriesDetails() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("seats", Arrays.asList("A12", "A13"));
        details.put("limit", 4);
        Map<String, Object> m = MiniJson.object(ErrorJson.body("seat_taken", "taken \"now\"", details));
        Map<?, ?> err = (Map<?, ?>) m.get("error");
        assertEquals("seat_taken", err.get("code"));
        assertEquals("taken \"now\"", err.get("message"));
        assertEquals(Arrays.asList("A12", "A13"), err.get("seats"));
        assertEquals(4L, err.get("limit"));
        assertTrue(m.containsKey("request_id"));
    }
}
