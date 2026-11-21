package com.loopone.loopinbe.global.oauth.apple;

import com.loopone.loopinbe.global.config.properties.AppleOAuthProperties;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.security.KeyFactory;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

@Slf4j
@Component
public class AppleClientSecretGenerator {
    private final AppleOAuthProperties properties;

    public AppleClientSecretGenerator(AppleOAuthProperties properties) {
        this.properties = properties;
    }

    public String generate(String clientId) {
        try {
            ECPrivateKey ecPrivateKey = loadPrivateKey(properties.privateKey());

            Instant now = Instant.now();
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(properties.teamId())
                    .subject(clientId)
                    .audience("https://appleid.apple.com")
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(now.plusSeconds(15777000))) // ~6개월
                    .build();

            JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.ES256)
                    .keyID(properties.keyId())
                    .build();

            SignedJWT jwt = new SignedJWT(header, claims);
            JWSSigner signer = new ECDSASigner(ecPrivateKey);
            jwt.sign(signer);

            return jwt.serialize();
        } catch (Exception e) {
            log.error("Apple client_secret 생성 실패: {}", e.getMessage(), e);
            throw new IllegalStateException("Apple client_secret 생성 실패", e);
        }
    }

    private ECPrivateKey loadPrivateKey(String privateKeyContent) throws Exception {
        String sanitized = privateKeyContent
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");

        byte[] keyBytes = Base64.getDecoder().decode(sanitized);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
        KeyFactory kf = KeyFactory.getInstance("EC");
        return (ECPrivateKey) kf.generatePrivate(spec);
    }
}
