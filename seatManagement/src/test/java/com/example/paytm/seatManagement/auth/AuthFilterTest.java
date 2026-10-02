package com.example.paytm.seatManagement.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The route table is the security perimeter, so it gets its own exhaustive test. */
class AuthFilterTest {

    private static void expect(AuthFilter.Access expected, String method, String path) {
        assertEquals(expected, AuthFilter.classify(method, path), method + " " + path);
    }

    @Test
    void publicReadOnlyEndpoints() {
        for (String p : new String[] {"/", "/healthz", "/readyz", "/metrics", "/logs"}) {
            expect(AuthFilter.Access.PUBLIC, "GET", p);
            expect(AuthFilter.Access.PUBLIC, "HEAD", p);
        }
        expect(AuthFilter.Access.USER, "GET", "/openapi.json"); // nothing serves it: stays deny-by-default
        expect(AuthFilter.Access.PUBLIC, "GET", "/shows/3f2c1a52-0000-4000-8000-000000000001");
        expect(AuthFilter.Access.PUBLIC, "POST", "/auth/token");
    }

    @Test
    void writesToPublicLookingPathsAreStillProtected() {
        for (String p : new String[] {"/", "/healthz", "/readyz", "/metrics", "/logs"}) {
            expect(AuthFilter.Access.USER, "POST", p);
            expect(AuthFilter.Access.USER, "DELETE", p);
            expect(AuthFilter.Access.USER, "PUT", p);
        }
        expect(AuthFilter.Access.USER, "GET", "/auth/token");
    }

    @Test
    void showCreationIsAdminOnly() {
        expect(AuthFilter.Access.ADMIN, "POST", "/shows");
    }

    @Test
    void reserveAndCancelNeedAUser() {
        expect(AuthFilter.Access.USER, "POST", "/shows/abc/reserve");
        expect(AuthFilter.Access.USER, "POST", "/reservations/abc/cancel");
        expect(AuthFilter.Access.USER, "GET", "/shows/abc/reserve");
        expect(AuthFilter.Access.USER, "GET", "/reservations/abc");
    }

    @Test
    void lookalikePathsDoNotSlipThrough() {
        expect(AuthFilter.Access.USER, "GET", "/shows/");
        expect(AuthFilter.Access.USER, "GET", "/shows");
        expect(AuthFilter.Access.USER, "GET", "/shows/abc/");
        expect(AuthFilter.Access.USER, "GET", "/shows/abc/reserve");
        expect(AuthFilter.Access.USER, "GET", "/healthz/");
        expect(AuthFilter.Access.USER, "GET", "/healthz;jsessionid=1");
        expect(AuthFilter.Access.USER, "GET", "/metrics/x");
        expect(AuthFilter.Access.USER, "GET", "//healthz");
        expect(AuthFilter.Access.USER, "POST", "/shows/abc");
        expect(AuthFilter.Access.USER, "GET", "/admin");
    }

    @Test
    void unknownMethodsAndPathsDefaultToProtected() {
        expect(AuthFilter.Access.USER, "TRACE", "/healthz");
        expect(AuthFilter.Access.USER, "OPTIONS", "/shows");
        expect(AuthFilter.Access.USER, "GET", "/does/not/exist");
    }
}
