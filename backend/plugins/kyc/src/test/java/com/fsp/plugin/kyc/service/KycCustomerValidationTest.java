package com.fsp.plugin.kyc.service;

import com.fsp.plugin.kyc.api.ApiProblemException;
import com.fsp.plugin.kyc.api.KycDtos;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Customer Registration (step 1 of 2) is validated server-side — the widget's checks are only a mirror. */
class KycCustomerValidationTest {

    private static KycDtos.CreateCustomerRequest req(String gender, LocalDate dob, String phone, String email, String primary) {
        return new KycDtos.CreateCustomerRequest("John", "Doe", gender, dob, phone, email, primary, HOME, true, null);
    }

    private static final KycDtos.AddressRequest HOME = new KycDtos.AddressRequest("12 Robert Mugabe Ave", "Windhoek", "KHOMAS", "10001");

    private static KycDtos.CreateCustomerRequest withAddress(KycDtos.AddressRequest home, Boolean same, KycDtos.AddressRequest postal) {
        return new KycDtos.CreateCustomerRequest("John", "Doe", "MALE", LocalDate.of(1990, 1, 1), null, "a@b.co", "EMAIL", home, same, postal);
    }

    @Test
    void theResidentialAddressIsRequiredLikeAnOrganizationsRegisteredAddress() {
        assertThatThrownBy(() -> KycCustomerService.validate(withAddress(null, true, null)))
            .isInstanceOf(ApiProblemException.class)
            .hasMessageContaining("residential street address")
            .hasMessageContaining("residential region");
        assertThatThrownBy(() -> KycCustomerService.validate(withAddress(
                new KycDtos.AddressRequest("12 Main St", "Windhoek", "ATLANTIS", null), true, null)))
            .hasMessageContaining("residential region");
    }

    @Test
    void aSeparatePostalAddressIsValidatedOnlyWhenItDiffers() {
        var same = KycCustomerService.validate(withAddress(HOME, true, null));
        assertThat(same.postal()).isNull();
        assertThat(same.residential().region()).isEqualTo("KHOMAS");
        assertThatThrownBy(() -> KycCustomerService.validate(withAddress(HOME, false, null)))
            .hasMessageContaining("postal street address");
        var po = KycCustomerService.validate(withAddress(HOME, false,
            new KycDtos.AddressRequest("PO Box 1234", "Windhoek", "KHOMAS", "9000")));
        assertThat(po.postal().line()).isEqualTo("PO Box 1234");
    }

    @Test
    void normalisesContactsAndDefaultsThePrimaryToEmail() {
        var v = KycCustomerService.validate(req("MALE", LocalDate.of(1990, 1, 1), "0026481 123-4567", " John@Example.com ", null));
        assertThat(v.email()).isEqualTo("john@example.com");
        assertThat(v.phone()).isEqualTo("+264811234567");
        assertThat(v.primaryContact()).isEqualTo("EMAIL");
    }

    @Test
    void thePrimaryContactMustBePresent() {
        assertThatThrownBy(() -> KycCustomerService.validate(req("MALE", LocalDate.of(1990, 1, 1), null, "a@b.co", "PHONE")))
            .isInstanceOf(ApiProblemException.class)
            .hasMessageContaining("Phone number is required");
    }

    @Test
    void genderComesFromTheMasterListAndBirthIsInThePast() {
        assertThatThrownBy(() -> KycCustomerService.validate(req("X", LocalDate.now().plusDays(1), null, "a@b.co", "EMAIL")))
            .isInstanceOf(ApiProblemException.class)
            .hasMessageContaining("Select a gender")
            .hasMessageContaining("past");
    }
}
