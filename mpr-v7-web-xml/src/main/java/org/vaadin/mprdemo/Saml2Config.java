package org.vaadin.mprdemo;

import java.io.InputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.saml2.core.Saml2X509Credential;
import org.springframework.security.saml2.provider.service.registration.InMemoryRelyingPartyRegistrationRepository;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistration;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrationRepository;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrations;
import org.springframework.security.saml2.provider.service.registration.Saml2MessageBinding;

/**
 * SAML2 Relying Party (SP) configuration.
 * <p>
 * Only active when the {@code saml2} Spring profile is enabled:
 * <pre>
 *   mvn spring-boot:run -Dspring-boot.run.profiles=saml2
 * </pre>
 * <p>
 * Requires a running SAML2 IdP (e.g. Keycloak). See {@code saml2/README.md}
 * for setup instructions.
 */
@Configuration
@Profile("saml2")
public class Saml2Config {

    @Value("${saml2.idp.metadata-uri}")
    private String idpMetadataUri;

    @Value("${saml2.sp.signing.key-location}")
    private Resource spKeyResource;

    @Value("${saml2.sp.signing.cert-location}")
    private Resource spCertResource;

    /**
     * Defines the SAML2 Relying Party (SP) registration that connects to the
     * IdP. The IdP metadata is fetched at startup from
     * {@code saml2.idp.metadata-uri}.
     * <p>
     * Single Logout (SLO) is enabled so that logout requests go through the
     * {@code Saml2LogoutRequestFilter} / {@code Saml2LogoutResponseFilter}
     * filter chain — matching the customer's Thread B stack trace.
     */
    @Bean
    public RelyingPartyRegistrationRepository relyingPartyRegistrations()
            throws Exception {
        RSAPrivateKey privateKey;
        X509Certificate certificate;

        try (InputStream keyIs = spKeyResource.getInputStream();
             InputStream certIs = spCertResource.getInputStream()) {
            privateKey = RsaKeyConverters.pkcs8().convert(keyIs);
            certificate = (X509Certificate) CertificateFactory
                    .getInstance("X.509")
                    .generateCertificate(certIs);
        }

        Saml2X509Credential signingCredential =
                Saml2X509Credential.signing(privateKey, certificate);
        Saml2X509Credential decryptionCredential =
                Saml2X509Credential.decryption(privateKey, certificate);

        RelyingPartyRegistration registration = RelyingPartyRegistrations
                .fromMetadataLocation(idpMetadataUri)
                .registrationId("keycloak")
                .entityId("{baseUrl}/saml2/service-provider-metadata/{registrationId}")
                .signingX509Credentials(c -> c.add(signingCredential))
                .decryptionX509Credentials(c -> c.add(decryptionCredential))
                // Single Logout (SLO) endpoints — the key to matching Thread B
                .singleLogoutServiceLocation(
                        "{baseUrl}/logout/saml2/slo")
                .singleLogoutServiceResponseLocation(
                        "{baseUrl}/logout/saml2/slo")
                .singleLogoutServiceBinding(Saml2MessageBinding.POST)
                .build();

        return new InMemoryRelyingPartyRegistrationRepository(registration);
    }
}
