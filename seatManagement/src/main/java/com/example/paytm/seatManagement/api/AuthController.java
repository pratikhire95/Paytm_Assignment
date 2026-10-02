package com.example.paytm.seatManagement.api;

import com.example.paytm.seatManagement.auth.TokenService;
import com.example.paytm.seatManagement.common.ApiException;
import com.example.paytm.seatManagement.common.Json;
import com.example.paytm.seatManagement.config.AppSettings;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Token issuance - a stand-in for a real identity provider.
 *
 * <p>Anyone may ask for a token for any {@code user_id}; the service's guarantees do not depend on WHO the user
 * is, only on identity being taken from a verified token and never from a request body. In production this
 * endpoint is switched off (ALLOW_TOKEN_MINT=false) and tokens come from the real IdP. Admin tokens are never
 * issued here: the admin credential is a separate static secret (ADMIN_TOKEN).
 */
@RestController
public class AuthController {

    private final TokenService tokens;
    private final AppSettings settings;

    public AuthController(TokenService tokens, AppSettings settings) {
        this.tokens = tokens;
        this.settings = settings;
    }

    @PostMapping("/auth/token")
    public ResponseEntity<String> token(@RequestBody(required = false) Map<String, Object> body) {
        if (!settings.tokenMintEnabled()) {
            throw new ApiException(404, "not_found", "Token issuance is disabled on this deployment");
        }
        Object userId = body == null ? null : body.get("user_id");
        if (!(userId instanceof String) || !TokenService.USER_ID.matcher((String) userId).matches()) {
            throw RequestValidator.bad("user_id is required: 1-64 characters, letters, digits and . _ @ : -");
        }
        String uid = (String) userId;
        String json = new Json.Obj()
                .str("token", tokens.mint(uid))
                .str("token_type", "Bearer")
                .str("user_id", uid)
                .num("expires_in", tokens.ttlSeconds())
                .build();
        return Http.json(200, json);
    }
}
