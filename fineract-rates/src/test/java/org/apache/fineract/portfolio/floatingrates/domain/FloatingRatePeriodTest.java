/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.fineract.portfolio.floatingrates.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.apache.fineract.portfolio.floatingrates.data.FloatingRateDTO;
import org.apache.fineract.portfolio.floatingrates.data.FloatingRatePeriodData;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class FloatingRatePeriodTest {

    private static final LocalDate FROM_DATE = LocalDate.of(2026, 4, 1);
    private static final List<FloatingRatePeriodData> BLR_NEWEST_FIRST = List.of(blr(LocalDate.of(2026, 5, 1), "4"), blr(FROM_DATE, "3"),
            blr(LocalDate.of(2026, 1, 1), "2"));

    @Test
    void toDataCarriesPeriodAttributesAndAddsLoanDifferential() {
        final FloatingRatePeriod period = new FloatingRatePeriod(FROM_DATE, new BigDecimal("5.25"), false, true);
        ReflectionTestUtils.setField(period, "id", 11L);

        final FloatingRatePeriodData data = period.toData(new FloatingRateDTO(false, FROM_DATE, new BigDecimal("0.75"), null));

        assertThat(data.getId()).isEqualTo(11L);
        assertThat(data.getFromDate()).isEqualTo(FROM_DATE);
        assertThat(data.getInterestRate()).isEqualByComparingTo("6.00");
        assertThat(data.getIsDifferentialToBaseLendingRate()).isFalse();
        assertThat(data.getIsActive()).isTrue();
    }

    @Test
    void toDataForDifferentialPeriodUsesBaseRateStartingOnSameDay() {
        final FloatingRatePeriod period = new FloatingRatePeriod(FROM_DATE, new BigDecimal("1"), true, true);

        final FloatingRatePeriodData data = period.toData(new FloatingRateDTO(true, FROM_DATE, BigDecimal.ZERO, BLR_NEWEST_FIRST));

        assertThat(data.getInterestRate()).isEqualByComparingTo("4");
        assertThat(data.getIsDifferentialToBaseLendingRate()).isTrue();
    }

    @Test
    void toDataForDifferentialPeriodUsesLatestBaseRateNotAfterPeriodStart() {
        final FloatingRatePeriod period = new FloatingRatePeriod(LocalDate.of(2026, 4, 20), new BigDecimal("1"), true, true);

        final FloatingRatePeriodData data = period.toData(new FloatingRateDTO(true, FROM_DATE, new BigDecimal("0.5"), BLR_NEWEST_FIRST));

        assertThat(data.getInterestRate()).isEqualByComparingTo("4.5");
    }

    @Test
    void toDataForDifferentialPeriodBeforeAnyBaseRateCurrentlyFailsWithNullPointer() {
        final FloatingRatePeriod period = new FloatingRatePeriod(LocalDate.of(2025, 12, 1), new BigDecimal("1"), true, true);
        final FloatingRateDTO dto = new FloatingRateDTO(true, FROM_DATE, BigDecimal.ZERO, BLR_NEWEST_FIRST);

        assertThatThrownBy(() -> period.toData(dto)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void toDataForDifferentialPeriodWithoutBaseLendingRateCurrentlyFailsWithNullPointer() {
        final FloatingRatePeriod period = new FloatingRatePeriod(FROM_DATE, new BigDecimal("1"), true, true);
        final FloatingRateDTO dto = new FloatingRateDTO(true, FROM_DATE, BigDecimal.ZERO, null);

        assertThatThrownBy(() -> period.toData(dto)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void setActiveTogglesPeriodState() {
        final FloatingRatePeriod period = new FloatingRatePeriod(FROM_DATE, BigDecimal.ONE, false, true);

        period.setActive(false);

        assertThat(period.isActive()).isFalse();
        assertThat(period.fetchFromDate()).isEqualTo(FROM_DATE);
    }

    private static FloatingRatePeriodData blr(final LocalDate fromDate, final String rate) {
        return new FloatingRatePeriodData(null, fromDate, new BigDecimal(rate), false, true);
    }
}
