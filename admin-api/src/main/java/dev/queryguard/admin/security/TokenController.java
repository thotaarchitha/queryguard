package dev.queryguard.admin.security;

import dev.queryguard.admin.AdminProperties;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Demo token issuer. Users come from config; a real deployment would put an IdP here. */
@RestController
public class TokenController {

    public record TokenRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record TokenResponse(String accessToken, String tokenType, long expiresIn) {
    }

    private final AdminProperties props;
    private final JwtEncoder encoder;

    public TokenController(AdminProperties props, JwtEncoder encoder) {
        this.props = props;
        this.encoder = encoder;
    }

    @Operation(summary = "Exchange demo credentials for a JWT")
    @PostMapping("/auth/token")
    public TokenResponse token(@Valid @RequestBody TokenRequest req) {
        AdminProperties.User user = props.users().get(req.username());
        // Compare even when the user doesn't exist, so timing doesn't reveal valid usernames.
        String expected = user == null ? "\u0000no-such-user" : user.password();
        boolean ok = MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), req.password().getBytes(StandardCharsets.UTF_8));
        if (user == null || !ok) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "bad username or password");
        }
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("queryguard-admin")
                .subject(req.username())
                .issuedAt(now)
                .expiresAt(now.plus(props.tokenTtl()))
                .claim("roles", user.roles())
                .build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new TokenResponse(token, "Bearer", props.tokenTtl().toSeconds());
    }
}
