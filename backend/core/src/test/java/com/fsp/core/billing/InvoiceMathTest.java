package com.fsp.core.billing;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The same numbers the widget previews (model.ts lineAmounts / dueDateFor) — server and UI never disagree. */
class InvoiceMathTest {

    @Test
    void taxRoundsHalfUpToTheMinorUnit() {
        assertThat(InvoiceMath.line(10, 12_000, 1500)).isEqualTo(new InvoiceMath.Amounts(120_000, 18_000, 138_000));
        assertThat(InvoiceMath.line(1, 333, 1500).tax()).isEqualTo(50);   // 49.95 → 50
        assertThat(InvoiceMath.line(1, 3, 1500).tax()).isZero();          // 0.45 → 0
        assertThat(InvoiceMath.line(1, 10, 1500).tax()).isEqualTo(2);     // 1.5 → 2
        assertThat(InvoiceMath.line(7, 999, 0)).isEqualTo(new InvoiceMath.Amounts(6_993, 0, 6_993));
    }

    @Test
    void overflowIsAnErrorNeverAWrongNumber() {
        assertThatThrownBy(() -> InvoiceMath.line(999_999, 9_999_999_999_999L, 1500)).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void thirtyDaysAfterIssueIsCalendarDays() {
        assertThat(InvoiceMath.dueDate(LocalDate.of(2026, 11, 24), 30)).isEqualTo(LocalDate.of(2026, 12, 24));
        assertThat(InvoiceMath.dueDate(LocalDate.of(2026, 2, 1), 30)).isEqualTo(LocalDate.of(2026, 3, 3));
        assertThat(InvoiceMath.dueDate(LocalDate.of(2026, 11, 24), 0)).isEqualTo(LocalDate.of(2026, 11, 24));
    }

    @Test
    void numbersAndAccountEndings() {
        assertThat(InvoiceMath.number(2026, 7)).isEqualTo("INV-2026-0007");
        assertThat(InvoiceMath.number(2026, 12345)).isEqualTo("INV-2026-12345");
        assertThat(InvoiceMath.accountEnding("62001234542")).isEqualTo("42");
        assertThat(InvoiceMath.accountEnding("1")).isEqualTo("••");
    }
}
