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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.apache.fineract.portfolio.floatingrates.data.FloatingRateDTO;
import org.apache.fineract.portfolio.floatingrates.data.FloatingRatePeriodData;
import org.apache.fineract.portfolio.floatingrates.exception.FloatingRateNotFoundException;
import org.junit.jupiter.api.Test;

class FloatingRatePeriodTest {

    private static final List<FloatingRatePeriodData> BASE_LENDING_RATE_PERIODS_NEWEST_FIRST = List.of(
            new FloatingRatePeriodData(2L, LocalDate.of(2024, 3, 1), new BigDecimal("4"), false, true),
            new FloatingRatePeriodData(1L, LocalDate.of(2024, 1, 1), new BigDecimal("3"), false, true));

    @Test
    void accessors_exposeConstructorValues() {
        final FloatingRatePeriod period = new FloatingRatePeriod(LocalDate.of(2024, 2, 1), new BigDecimal("2.5"), true, true);
        final FloatingRate rate = new FloatingRate();

        period.updateFloatingRate(rate);
        period.setActive(false);

        assertThat(period.getFromDate()).isEqualTo(LocalDate.of(2024, 2, 1));
        assertThat(period.fetchFromDate()).isEqualTo(period.getFromDate());
        assertThat(period.getInterestRate()).isEqualByComparingTo("2.5");
        assertThat(period.isDifferentialToBaseLendingRate()).isTrue();
        assertThat(period.isActive()).isFalse();
        assertThat(period.getFloatingRate()).isSameAs(rate);
    }

    @Test
    void toData_absoluteRate_addsOnlyLoanDifferential() {
        final FloatingRatePeriod period = new FloatingRatePeriod(LocalDate.of(2024, 2, 1), new BigDecimal("5"), false, true);

        final FloatingRatePeriodData data = period.toData(dto(new BigDecimal("1.25"), BASE_LENDING_RATE_PERIODS_NEWEST_FIRST));

        assertThat(data.getInterestRate()).isEqualByComparingTo("6.25");
        assertThat(data.getFromDate()).isEqualTo(LocalDate.of(2024, 2, 1));
        assertThat(data.getIsDifferentialToBaseLendingRate()).isFalse();
        assertThat(data.getIsActive()).isTrue();
    }

    @Test
    void toData_differentialRate_addsBaseLendingRateInEffectOnPeriodStart() {
        final FloatingRatePeriod period = new FloatingRatePeriod(LocalDate.of(2024, 2, 1), new BigDecimal("2"), true, true);

        final FloatingRatePeriodData data = period.toData(dto(new BigDecimal("0.5"), BASE_LENDING_RATE_PERIODS_NEWEST_FIRST));

        assertThat(data.getInterestRate()).isEqualByComparingTo("5.5");
        assertThat(data.getIsDifferentialToBaseLendingRate()).isTrue();
    }

    @Test
    void toData_differentialRate_onBaseRateChangeDate_usesNewBaseRate() {
        final FloatingRatePeriod period = new FloatingRatePeriod(LocalDate.of(2024, 3, 1), new BigDecimal("2"), true, true);

        final FloatingRatePeriodData data = period.toData(dto(new BigDecimal("0.5"), BASE_LENDING_RATE_PERIODS_NEWEST_FIRST));

        assertThat(data.getInterestRate()).isEqualByComparingTo("6.5");
    }

    @Test
    void toData_inactivePeriod_isReportedInactive() {
        final FloatingRatePeriod period = new FloatingRatePeriod(LocalDate.of(2024, 2, 1), new BigDecimal("5"), false, false);

        assertThat(period.toData(dto(BigDecimal.ZERO, List.of())).getIsActive()).isFalse();
    }

    // Likely bug (documented in PR): no base lending rate period on or before the differential period start.
    @Test
    void toData_differentialRate_startingBeforeAnyBaseRatePeriod_throwsNullPointerException() {
        final FloatingRatePeriod period = new FloatingRatePeriod(LocalDate.of(2023, 12, 1), new BigDecimal("2"), true, true);
        final FloatingRateDTO dto = dto(BigDecimal.ZERO, BASE_LENDING_RATE_PERIODS_NEWEST_FIRST);

        assertThatThrownBy(() -> period.toData(dto)).isInstanceOf(NullPointerException.class);
    }

    // Likely bug (documented in PR): LoanScheduleAssembler passes null periods when no base lending rate exists.
    @Test
    void toData_differentialRate_withoutBaseLendingRatePeriods_throwsNullPointerException() {
        final FloatingRatePeriod period = new FloatingRatePeriod(LocalDate.of(2024, 2, 1), new BigDecimal("2"), true, true);
        final FloatingRateDTO dto = dto(BigDecimal.ZERO, null);

        assertThatThrownBy(() -> period.toData(dto)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void repositoryWrapper_findOne_returnsExistingRate() {
        final FloatingRateRepository repository = mock(FloatingRateRepository.class);
        final FloatingRate rate = new FloatingRate();
        when(repository.findById(3L)).thenReturn(Optional.of(rate));

        assertThat(new FloatingRateRepositoryWrapper(repository).findOneWithNotFoundDetection(3L)).isSameAs(rate);
    }

    @Test
    void repositoryWrapper_findOne_unknownId_throwsNotFound() {
        final FloatingRateRepository repository = mock(FloatingRateRepository.class);
        when(repository.findById(4L)).thenReturn(Optional.empty());
        final FloatingRateRepositoryWrapper wrapper = new FloatingRateRepositoryWrapper(repository);

        assertThatThrownBy(() -> wrapper.findOneWithNotFoundDetection(4L)).isInstanceOf(FloatingRateNotFoundException.class)
                .extracting("globalisationMessageCode").isEqualTo("error.msg.floatingrate.id.invalid");
    }

    @Test
    void repositoryWrapper_delegatesBaseLendingRateLookupAndSaves() {
        final FloatingRateRepository repository = mock(FloatingRateRepository.class);
        final FloatingRate rate = new FloatingRate();
        when(repository.retrieveBaseLendingRate()).thenReturn(rate);
        final FloatingRateRepositoryWrapper wrapper = new FloatingRateRepositoryWrapper(repository);

        assertThat(wrapper.retrieveBaseLendingRate()).isSameAs(rate);
        wrapper.save(rate);
        wrapper.saveAndFlush(rate);

        verify(repository).save(rate);
        verify(repository).saveAndFlush(rate);
    }

    private static FloatingRateDTO dto(final BigDecimal loanDifferential, final List<FloatingRatePeriodData> baseLendingRatePeriods) {
        return new FloatingRateDTO(true, LocalDate.of(2024, 1, 1), loanDifferential, baseLendingRatePeriods);
    }
}
