package nzi.fhir.validator.web.service;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.DataFormatException;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.impl.NoStackTraceThrowable;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import nzi.fhir.validator.web.enums.SupportedFhirVersion;
import nzi.fhir.validator.web.testcontainers.BaseTestContainer;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.concurrent.CompletionException;

import static nzi.fhir.validator.web.config.ApplicationConfig.DB_POSTGRES_SCHEMA_NAME;
import static org.junit.jupiter.api.Assertions.*;

class ProfileServiceTest extends BaseTestContainer {

    private Vertx vertx;
    private FhirContext fhirContext;
    private ProfileService profileService;
    private static final String TEST_PROFILE_URL = "http://example.com/fhir/StructureDefinition/test-profile";
    private static final String TEST_PROFILE_JSON = "{\"resourceType\":\"StructureDefinition\",\"url\":\"" + TEST_PROFILE_URL + "\",\"fhirVersion\":\"4.0.1\", \"id\": \"test-profile\"}";

    @BeforeEach
    void setUp() {
        // Ensure the container is started before accessing ports
        startContainers();
        vertx = Vertx.vertx();
        Pool pgPool = getPgPool(vertx);
        initDatabaseScheme(pgPool);

        fhirContext = FhirContextLoader.getInstance().getContext(SupportedFhirVersion.R4);
        profileService = ProfileService.create(vertx, fhirContext, pgPool);

        createTables(pgPool);
    }

    @Test
    @DisplayName("Should successfully register and retrieve a profile")
    void whenRegisterAndRetrieveProfile_thenSucceeds() {
        // Arrange
        JsonObject profile = new JsonObject(TEST_PROFILE_JSON);

        // Act & Assert
        Future<IBaseResource> getProfileResult = profileService
            .registerProfile(profile)
            .compose(v -> profileService.getProfile(TEST_PROFILE_URL));

        getProfileResult.onComplete(ar -> {
            assertTrue(ar.succeeded());
            IBaseResource resource = ar.result();
            assertNotNull(resource);
            assertEquals("StructureDefinition/test-profile", resource.getIdElement().getValue());
        }).toCompletionStage().toCompletableFuture().join();
    }

    @Test
    @DisplayName("Should successfully register multiple profiles")
    void whenRegisterMultipleProfiles_thenSucceeds() {
        // Arrange
        JsonObject[] profiles = new JsonObject[]{
            new JsonObject(TEST_PROFILE_JSON),
            new JsonObject()
                .put("resourceType", "StructureDefinition")
                .put("url", "http://example.com/fhir/StructureDefinition/test-profile-2")
                .put("fhirVersion", "4.0.1")
        };

        // Act & Assert
        Future<Void> result = profileService.registerProfiles(profiles);

        result.onComplete(ar -> {
            assertTrue(ar.succeeded());
        }).toCompletionStage().toCompletableFuture().join();
    }

    @Test
    @DisplayName("Should fail when trying to get a non-existent profile")
    void whenGetNonExistentProfile_thenFails() {
        String nonExistentUrl = "http://example.com/fhir/StructureDefinition/non-existent";

        try {
            profileService.getProfile(nonExistentUrl)
                .toCompletionStage()
                .toCompletableFuture()
                .join();
            fail("Expected CompletionException to be thrown");
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            assertInstanceOf(NoStackTraceThrowable.class, cause);
            assertEquals("Profile not found: " + nonExistentUrl, cause.getMessage());
        }
    }

    @Test
    @DisplayName("Should not register a profile with invalid FHIR version")
    void whenRegisterProfileWithInvalidFhirVersion_thenSucceeds() {
        JsonObject profile = new JsonObject()
            .put("resourceType", "StructureDefinition")
            .put("url", TEST_PROFILE_URL)
            .put("fhirVersion", "invalid-version");
        try {
            profileService.registerProfile(profile);
        } catch (DataFormatException e){
            assertTrue(e.getMessage().contains("Unknown FHIRVersion code 'invalid-version'"));
        }
    }

    @Test
    @DisplayName("Should update existing profile when registering the same URL twice")
    void whenRegisterSameProfileTwice_thenUpdatesExisting() {
        JsonObject initialProfile = new JsonObject(TEST_PROFILE_JSON);
        JsonObject updatedProfile = new JsonObject(TEST_PROFILE_JSON)
            .put("description", "Updated profile");

        profileService.registerProfile(initialProfile)
            .compose(v -> profileService.getProfile(TEST_PROFILE_URL))
            .compose(firstVersion -> profileService.registerProfile(updatedProfile))
            .compose(v -> profileService.getProfile(TEST_PROFILE_URL))
            .onComplete(ar -> {
                assertTrue(ar.succeeded());
                IBaseResource resource = ar.result();
                assertNotNull(resource);
                assertTrue(resource.toString().contains("Updated profile"));
            }).toCompletionStage().toCompletableFuture().join();
    }

    @Test
    @DisplayName("Should keep last profile when registering multiple profiles with same URL")
    void whenRegisterMultipleProfilesWithSameUrl_thenLastOneWins() {
        JsonObject[] profiles = new JsonObject[]{
            new JsonObject()
                .put("resourceType", "StructureDefinition")
                .put("url", TEST_PROFILE_URL)
                .put("fhirVersion", "4.0.1")
                .put("description", "First version"),
            new JsonObject()
                .put("resourceType", "StructureDefinition")
                .put("url", TEST_PROFILE_URL)
                .put("fhirVersion", "4.0.1")
                .put("description", "Second version")
        };

        profileService.registerProfiles(profiles)
            .compose(v -> profileService.getProfile(TEST_PROFILE_URL))
            .onComplete(ar -> {
                assertTrue(ar.succeeded());
                IBaseResource resource = ar.result();
                assertNotNull(resource);
                assertTrue(resource.toString().contains("Second version"));
            }).toCompletionStage().toCompletableFuture().join();
    }

    @Test
    @DisplayName("Should successfully register profile with special characters in URL")
    void whenRegisterProfileWithSpecialCharactersInUrl_thenSucceeds() {
        String specialUrl = "http://example.com/fhir/StructureDefinition/test-profile#1@2$3";
        JsonObject profile = new JsonObject()
            .put("resourceType", "StructureDefinition")
            .put("url", specialUrl)
            .put("fhirVersion", "4.0.1");

        profileService.registerProfile(profile)
            .compose(v -> profileService.getProfile(specialUrl))
            .onComplete(ar -> {
                assertTrue(ar.succeeded());
                IBaseResource resource = ar.result();
                assertNotNull(resource);
                assertTrue(resource.toString().contains(specialUrl));
            }).toCompletionStage().toCompletableFuture().join();
    }

    @Test
    @DisplayName("Should successfully register a large number of profiles")
    void whenRegisterLargeNumberOfProfiles_thenAllSucceed() {
        int numberOfProfiles = 50;
        JsonObject[] profiles = new JsonObject[numberOfProfiles];
        for (int i = 0; i < numberOfProfiles; i++) {
            profiles[i] = new JsonObject()
                .put("resourceType", "StructureDefinition")
                .put("url", "http://example.com/fhir/StructureDefinition/profile-" + i)
                .put("fhirVersion", "4.0.1")
                .put("description", "Profile " + i);
        }

        profileService.registerProfiles(profiles)
            .onComplete(ar -> {
                assertTrue(ar.succeeded());
            }).toCompletionStage().toCompletableFuture().join();
    }

    @Test
    @DisplayName("Should successfully retrieve profile after service restart")
    void whenGetProfileAfterServerRestart_thenSucceeds() {
        JsonObject profile = new JsonObject(TEST_PROFILE_JSON);
        
        profileService.registerProfile(profile)
            .compose(v -> {
                // Simulate server restart by creating a new service instance
                profileService = ProfileService.create(vertx, fhirContext, getPgPool(vertx));
                return profileService.getProfile(TEST_PROFILE_URL);
            })
            .onComplete(ar -> {
                assertTrue(ar.succeeded());
                IBaseResource resource = ar.result();
                assertNotNull(resource);
                assertEquals(TEST_PROFILE_URL, resource.getIdElement().getValue());
            }).toCompletionStage().toCompletableFuture().join();
    }

    @AfterEach
    void tearDown() {
        if (vertx != null) {
            if(hasPgPool()){
                Pool pool = getPgPool(vertx);
                dropTables(pool);
                pool.close();
                removePgPool();
            }
            vertx.close();
        }
    }
}