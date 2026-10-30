package wevioo.backend.service;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import wevioo.backend.config.AppProperties;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** Issues the signed, expiring bearer tokens returned by {@code POST /api/auth/login}. */
@Service
public class TokenService {
    private final JwtEncoder encoder;
    private final AppProperties properties;

    public TokenService(JwtEncoder encoder, AppProperties properties) {
        this.encoder = encoder;
        this.properties = properties;
    }

    public IssuedToken issue(Authentication authentication) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.getJwtTtlMinutes(), ChronoUnit.MINUTES);
        List<String> roles = roles(authentication);
        JwtClaimsSet claims = JwtClaimsSet.builder()
            .issuer("dq-platform")
            .subject(authentication.getName())
            .issuedAt(now)
            .expiresAt(expiresAt)
            .claim("roles", roles)
            .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(token, expiresAt, authentication.getName(), roles);
    }

    public static List<String> roles(Authentication authentication) {
        return authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            // Spring Security also adds FACTOR_* authorities (how the user authenticated); keep roles only.
            .filter(authority -> authority.startsWith("ROLE_"))
            .map(authority -> authority.substring("ROLE_".length()))
            .toList();
    }

    public record IssuedToken(String token, Instant expiresAt, String username, List<String> roles) {}
}
