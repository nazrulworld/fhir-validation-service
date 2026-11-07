package nzi.fhir.validator.core.org;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every {@link Organisation#validate()} rule (data-model §1.1): each boundary value is accepted, one past it is
 * refused, and the message names the field.
 */
class OrganisationTest {

    private static Organisation valid() {
        return Organisation.empty()
                .with(Organisation.NAME_SHORT, "acme")
                .with(Organisation.NAME_LONG, "Acme Health ApS")
                .with(Organisation.TYPE, "COMMERCIAL");
    }

    private static void accepted(Organisation o) {
        assertDoesNotThrow(o::validate);
    }

    private static void refused(Organisation o, String field) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, o::validate);
        assertTrue(e.getMessage().contains(field), "message names " + field + ": " + e.getMessage());
    }

    @Test
    void minimalOrganisationIsValid() {
        accepted(valid());
    }

    @Test
    void nameShort() {
        accepted(valid().with(Organisation.NAME_SHORT, "ab"));
        accepted(valid().with(Organisation.NAME_SHORT, "a".repeat(32)));
        accepted(valid().with(Organisation.NAME_SHORT, "A_b-9"));
        refused(valid().with(Organisation.NAME_SHORT, "a"), "nameShort");
        refused(valid().with(Organisation.NAME_SHORT, "a".repeat(33)), "nameShort");
        refused(valid().with(Organisation.NAME_SHORT, "ac me"), "nameShort");
        refused(valid().with(Organisation.NAME_SHORT, "acmé"), "nameShort");
        refused(valid().with(Organisation.NAME_SHORT, null), "nameShort");
    }

    @Test
    void nameLong() {
        accepted(valid().with(Organisation.NAME_LONG, "A"));
        accepted(valid().with(Organisation.NAME_LONG, "x".repeat(256)));
        refused(valid().with(Organisation.NAME_LONG, "x".repeat(257)), "nameLong");
        refused(valid().with(Organisation.NAME_LONG, " padded"), "nameLong");
        refused(valid().with(Organisation.NAME_LONG, "   "), "nameLong");
        refused(valid().with(Organisation.NAME_LONG, null), "nameLong");
    }

    @Test
    void type() {
        accepted(valid().with(Organisation.TYPE, "NON_COMMERCIAL"));
        accepted(valid().with(Organisation.TYPE, "commercial"));
        refused(valid().with(Organisation.TYPE, null), "type");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> valid().with(Organisation.TYPE, "GOVERNMENT"));
        assertTrue(e.getMessage().contains("type"));
    }

    @Test
    void addressLines() {
        accepted(valid().withAddressLines(Collections.nCopies(6, "x".repeat(128))));
        refused(valid().withAddressLines(Collections.nCopies(7, "line")), "addressLines");
        refused(valid().withAddressLines(List.of("x".repeat(129))), "addressLines");
        refused(valid().withAddressLines(List.of("")), "addressLines");
    }

    @Test
    void postCode() {
        accepted(valid().with(Organisation.POST_CODE, "x".repeat(16)));
        refused(valid().with(Organisation.POST_CODE, "x".repeat(17)), "postCode");
    }

    @Test
    void country() {
        accepted(valid().with(Organisation.COUNTRY, "DK"));
        refused(valid().with(Organisation.COUNTRY, "dk"), "country");
        refused(valid().with(Organisation.COUNTRY, "DNK"), "country");
        refused(valid().with(Organisation.COUNTRY, "XX"), "country");
    }

    @Test
    void emails() {
        for (String field : List.of(Organisation.EMAIL, Organisation.CONTACT_PERSON_EMAIL)) {
            accepted(valid().with(field, "ops@acme.example"));
            String local = "a".repeat(254 - "@acme.example".length());
            accepted(valid().with(field, local + "@acme.example"));
            refused(valid().with(field, local + "a@acme.example"), field);
            refused(valid().with(field, "not-an-address"), field);
            refused(valid().with(field, "two@@acme.example"), field);
            refused(valid().with(field, "space @acme.example"), field);
        }
    }

    @Test
    void phones() {
        for (String field : List.of(Organisation.PHONE, Organisation.CONTACT_PERSON_MOBILE)) {
            accepted(valid().with(field, "+45 12 34 56 78"));
            accepted(valid().with(field, "123"));
            accepted(valid().with(field, "1".repeat(32)));
            refused(valid().with(field, "12"), field);
            refused(valid().with(field, "1".repeat(33)), field);
            refused(valid().with(field, "call me"), field);
        }
    }

    @Test
    void website() {
        accepted(valid().with(Organisation.WEBSITE, "https://acme.example"));
        accepted(valid().with(Organisation.WEBSITE, "http://acme.example/" + "p".repeat(256 - "http://acme.example/".length())));
        refused(valid().with(Organisation.WEBSITE, "http://acme.example/" + "p".repeat(257 - "http://acme.example/".length())), "website");
        refused(valid().with(Organisation.WEBSITE, "ftp://acme.example"), "website");
        refused(valid().with(Organisation.WEBSITE, "acme.example"), "website");
    }

    @Test
    void contactPersonName() {
        accepted(valid().with(Organisation.CONTACT_PERSON_NAME, "x".repeat(128)));
        refused(valid().with(Organisation.CONTACT_PERSON_NAME, "x".repeat(129)), "contactPersonName");
    }

    @Test
    void emptyValueClearsAnOptionalField() {
        Organisation o = valid().with(Organisation.EMAIL, "ops@acme.example").with(Organisation.EMAIL, "");
        assertNull(o.email());
    }

    @Test
    void jsonRoundTrip() {
        Organisation o = valid().with(Organisation.COUNTRY, "DK").withAddressLines(List.of("Street 1", "2. sal"));
        Organisation.Input in = Organisation.fromJson(o.toJson());
        assertEquals(o.nameShort(), in.organisation().nameShort());
        assertEquals(o.addressLines(), in.organisation().addressLines());
        assertEquals(o.type(), in.organisation().type());
        assertTrue(in.fields().containsAll(List.of(Organisation.NAME_SHORT, Organisation.COUNTRY, Organisation.ADDRESS_LINES)));
    }

    @Test
    void fromJsonRefusesUnknownFieldsAndIgnoresOutputOnlyOnes() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Organisation.fromJson(new JsonObject().put("nameShort", "acme").put("colour", "red")));
        assertTrue(e.getMessage().contains("colour"));
        Organisation.Input in = Organisation.fromJson(new JsonObject().put("nameShort", "acme").put("id", "x")
                .put("active", false).put("createdAt", "2026-01-01T00:00:00Z"));
        assertEquals(java.util.Set.of(Organisation.NAME_SHORT), in.fields());
        assertThrows(IllegalArgumentException.class,
                () -> Organisation.fromJson(new JsonObject().put("addressLines", new JsonArray().add(1))));
    }
}
