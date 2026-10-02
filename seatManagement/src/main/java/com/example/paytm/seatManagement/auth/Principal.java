package com.example.paytm.seatManagement.auth;

/** The authenticated caller. Always derived from a verified bearer token, never from a request body. */
public final class Principal {

    public static final String ROLE_USER = "user";
    public static final String ROLE_ADMIN = "admin";
    public static final String ADMIN_USER_ID = "admin";

    private final String userId;
    private final String role;

    public Principal(String userId, String role) {
        this.userId = userId;
        this.role = role;
    }

    public static Principal admin() {
        return new Principal(ADMIN_USER_ID, ROLE_ADMIN);
    }

    public String userId() {
        return userId;
    }

    public String role() {
        return role;
    }

    public boolean isAdmin() {
        return ROLE_ADMIN.equals(role);
    }

    @Override
    public String toString() {
        return "Principal[" + userId + "," + role + "]";
    }
}
