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
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.portfolio.floatingrates.data.FloatingRateDTO;
import org.apache.fineract.portfolio.floatingrates.data.FloatingRatePeriodData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class FloatingRateTest {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2024, 6, 15);
    private static final LocalDate JAN = LocalDate.of(2024, 1, 1);
    private static final LocalDate APR = LocalDate.of(2024, 4, 1);
    private static final LocalDate JUL = LocalDate.of(2024, 7, 1);

    @BeforeEach
    public void setUp() {
        ThreadLocalContextUtil.setBusinessDates(new HashMap<>(Map.of(BusinessDateType.BUSINESS_DATE, BUSINESS_DATE)));
    }

    @AfterEach
    public void tearDown() {
        ThreadLocalContextUtil.reset();
    }

    @Test
    public void constructorLinksEveryRatePeriodBackToTheRate() {
        final FloatingRatePeriod first = period(JAN, "5", false, true);
        final FloatingRatePeriod second = period(APR, "6", false, true);

        final FloatingRate rate = new FloatingRate("Rate", true, false, new ArrayList<>(List.of(first, second)));

        assertThat(rate.getName()).isEqualTo("Rate");
        assertThat(rate.isBaseLendingRate()).isTrue();
        assertThat(rate.isActive()).isFalse();
        assertThat(rate.getFloatingRatePeriods()).containsExactly(first, second);
        assertThat(first.getFloatingRate()).isSameAs(rate);
        assertThat(second.getFloatingRate()).isSameAs(rate);
    }

    @Test
    public void constructorAcceptsNullRatePeriods() {
        final FloatingRate rate = new FloatingRate("Rate", false, true, null);

        assertThat(rate.getFloatingRatePeriods()).isNull();
    }

    @Test
    public void settersUpdateNameBaseLendingFlagAndActiveFlag() {
        final FloatingRate rate = new FloatingRate();

        rate.setName("Renamed");
        rate.setBaseLendingRate(true);
        rate.setActive(true);

        assertThat(rate.getName()).isEqualTo("Renamed");
        assertThat(rate.isBaseLendingRate()).isTrue();
        assertThat(rate.isActive()).isTrue();
    }

    @Test
    public void replaceRatePeriodsDeactivatesOnlyFuturePeriodsAndAppendsNewOnes() {
        final FloatingRatePeriod past = period(JAN, "5", false, true);
        final FloatingRatePeriod today = period(BUSINESS_DATE, "6", false, true);
        final FloatingRatePeriod future = period(JUL, "7", false, true);
        final FloatingRate rate = new FloatingRate("Rate", false, true, new ArrayList<>(List.of(past, today, future)));
        final FloatingRatePeriod replacement = period(LocalDate.of(2024, 8, 1), "8", false, true);

        rate.replaceRatePeriods(List.of(replacement));

        assertThat(past.isActive()).isTrue();
        assertThat(today.isActive()).isTrue();
        assertThat(future.isActive()).isFalse();
        assertThat(rate.getFloatingRatePeriods()).containsExactly(past, today, future, replacement);
        assertThat(replacement.getFloatingRate()).isSameAs(rate);
    }

    @Test
    public void replaceRatePeriodsOnRateWithoutPeriodsCurrentlyThrowsNullPointerException() {
        // Characterises current behaviour: the null guard only protects the deactivation loop, not the append.
        final FloatingRate rate = new FloatingRate("Rate", false, true, null);
        final List<FloatingRatePeriod> newPeriods = List.of(period(JUL, "7", false, true));

        assertThatThrownBy(() -> rate.replaceRatePeriods(newPeriods)).isInstanceOf(NullPointerException.class);
    }

    @Test
    public void fetchInterestRatesReturnsNothingWhenThereAreNoActivePeriods() {
        final FloatingRate rate = new FloatingRate("Rate", false, true,
                new ArrayList<>(List.of(period(JAN, "5", false, false), period(APR, "6", false, false))));

        assertThat(rate.fetchInterestRates(dto(false, LocalDate.of(2024, 2, 1)))).isEmpty();
    }

    @Test
    public void fetchInterestRatesNonFloatingReturnsThePeriodInEffectOnTheStartDate() {
        final FloatingRate rate = threePeriodRate();

        final Collection<FloatingRatePeriodData> rates = rate.fetchInterestRates(dto(false, LocalDate.of(2024, 5, 10)));

        assertThat(rates).extracting(FloatingRatePeriodData::getFromDate).containsExactly(APR);
        assertThat(rates).extracting(FloatingRatePeriodData::getInterestRate).containsExactly(new BigDecimal("6"));
    }

    @Test
    public void fetchInterestRatesTreatsThePeriodFromDateAsInclusive() {
        final FloatingRate rate = threePeriodRate();

        final Collection<FloatingRatePeriodData> rates = rate.fetchInterestRates(dto(false, APR));

        assertThat(rates).extracting(FloatingRatePeriodData::getFromDate).containsExactly(APR);
    }

    @Test
    public void fetchInterestRatesNonFloatingBeforeFirstPeriodFallsBackToFirstPeriod() {
        final FloatingRate rate = threePeriodRate();

        final Collection<FloatingRatePeriodData> rates = rate.fetchInterestRates(dto(false, LocalDate.of(2023, 12, 1)));

        assertThat(rates).extracting(FloatingRatePeriodData::getFromDate).containsExactly(JAN);
    }

    @Test
    public void fetchInterestRatesNonFloatingAfterLastPeriodReturnsLastPeriod() {
        final FloatingRate rate = threePeriodRate();

        final Collection<FloatingRatePeriodData> rates = rate.fetchInterestRates(dto(false, LocalDate.of(2025, 1, 1)));

        assertThat(rates).extracting(FloatingRatePeriodData::getFromDate).containsExactly(JUL);
    }

    @Test
    public void fetchInterestRatesFloatingReturnsPeriodInEffectAndAllLaterPeriods() {
        final FloatingRate rate = threePeriodRate();

        final Collection<FloatingRatePeriodData> rates = rate.fetchInterestRates(dto(true, LocalDate.of(2024, 2, 1)));

        assertThat(rates).extracting(FloatingRatePeriodData::getFromDate).containsExactly(JAN, APR, JUL);
    }

    @Test
    public void fetchInterestRatesFloatingBeforeFirstPeriodReturnsAllPeriods() {
        final FloatingRate rate = threePeriodRate();

        final Collection<FloatingRatePeriodData> rates = rate.fetchInterestRates(dto(true, LocalDate.of(2023, 12, 1)));

        assertThat(rates).extracting(FloatingRatePeriodData::getFromDate).containsExactly(JAN, APR, JUL);
    }

    @Test
    public void fetchInterestRatesFloatingAfterLastPeriodReturnsOnlyLastPeriod() {
        final FloatingRate rate = threePeriodRate();

        final Collection<FloatingRatePeriodData> rates = rate.fetchInterestRates(dto(true, LocalDate.of(2025, 1, 1)));

        assertThat(rates).extracting(FloatingRatePeriodData::getFromDate).containsExactly(JUL);
    }

    @Test
    public void fetchInterestRatesIgnoresInactivePeriods() {
        final FloatingRate rate = new FloatingRate("Rate", false, true,
                new ArrayList<>(List.of(period(JAN, "5", false, true), period(APR, "6", false, false), period(JUL, "7", false, true))));

        final Collection<FloatingRatePeriodData> rates = rate.fetchInterestRates(dto(false, LocalDate.of(2024, 5, 10)));

        assertThat(rates).extracting(FloatingRatePeriodData::getFromDate).containsExactly(JAN);
    }

    @Test
    public void fetchInterestRatesAddsLoanDifferentialToEachPeriodRate() {
        final FloatingRate rate = threePeriodRate();

        final Collection<FloatingRatePeriodData> rates = rate
                .fetchInterestRates(new FloatingRateDTO(true, LocalDate.of(2024, 5, 10), new BigDecimal("1.5"), List.of()));

        assertThat(rates).extracting(FloatingRatePeriodData::getInterestRate).containsExactly(new BigDecimal("7.5"), new BigDecimal("8.5"));
    }

    @Test
    public void toDataAddsBaseLendingRateInEffectOnPeriodFromDateForDifferentialPeriods() {
        final FloatingRatePeriod differential = period(APR, "2", true, true);
        final List<FloatingRatePeriodData> blrPeriodsNewestFirst = List.of(blr(JUL, "9"), blr(LocalDate.of(2024, 3, 1), "4"),
                blr(JAN, "3"));

        final FloatingRatePeriodData data = differential
                .toData(new FloatingRateDTO(false, APR, new BigDecimal("0.5"), blrPeriodsNewestFirst));

        assertThat(data.getInterestRate()).isEqualByComparingTo("6.5");
        assertThat(data.getIsDifferentialToBaseLendingRate()).isTrue();
        assertThat(data.getIsActive()).isTrue();
        assertThat(data.getFromDate()).isEqualTo(APR);
    }

    @Test
    public void toDataUsesBaseLendingRatePeriodStartingOnTheSameDay() {
        final FloatingRatePeriod differential = period(APR, "2", true, true);

        final FloatingRatePeriodData data = differential
                .toData(new FloatingRateDTO(false, APR, BigDecimal.ZERO, List.of(blr(APR, "4"), blr(JAN, "3"))));

        assertThat(data.getInterestRate()).isEqualByComparingTo("6");
    }

    @Test
    public void toDataIgnoresBaseLendingRateForNonDifferentialPeriods() {
        final FloatingRatePeriod plain = period(APR, "2", false, true);

        final FloatingRatePeriodData data = plain.toData(new FloatingRateDTO(false, APR, BigDecimal.ONE, List.of(blr(JAN, "3"))));

        assertThat(data.getInterestRate()).isEqualByComparingTo("3");
        assertThat(data.getIsDifferentialToBaseLendingRate()).isFalse();
    }

    @Test
    public void toDataDifferentialPeriodWithoutCoveringBaseLendingRateCurrentlyThrowsNullPointerException() {
        // Characterises current behaviour: fetchBaseRate returns null when no BLR period starts on or before fromDate.
        final FloatingRatePeriod differential = period(JAN, "2", true, true);
        final FloatingRateDTO dto = new FloatingRateDTO(false, JAN, BigDecimal.ZERO, List.of(blr(APR, "4")));

        assertThatThrownBy(() -> differential.toData(dto)).isInstanceOf(NullPointerException.class);
    }

    @Test
    public void ratePeriodExposesItsStateAndCanBeDeactivated() {
        final FloatingRatePeriod ratePeriod = period(APR, "6.25", true, true);

        ratePeriod.setActive(false);

        assertThat(ratePeriod.getFromDate()).isEqualTo(APR);
        assertThat(ratePeriod.fetchFromDate()).isEqualTo(APR);
        assertThat(ratePeriod.getInterestRate()).isEqualByComparingTo("6.25");
        assertThat(ratePeriod.isDifferentialToBaseLendingRate()).isTrue();
        assertThat(ratePeriod.isActive()).isFalse();
        assertThat(new FloatingRatePeriod().getFromDate()).isNull();
    }

    private static FloatingRate threePeriodRate() {
        return new FloatingRate("Rate", false, true,
                new ArrayList<>(List.of(period(JAN, "5", false, true), period(APR, "6", false, true), period(JUL, "7", false, true))));
    }

    private static FloatingRatePeriod period(final LocalDate fromDate, final String rate, final boolean differential,
            final boolean active) {
        return new FloatingRatePeriod(fromDate, new BigDecimal(rate), differential, active);
    }

    private static FloatingRateDTO dto(final boolean floating, final LocalDate startDate) {
        return new FloatingRateDTO(floating, startDate, BigDecimal.ZERO, List.of());
    }

    private static FloatingRatePeriodData blr(final LocalDate fromDate, final String rate) {
        return new FloatingRatePeriodData(null, fromDate, new BigDecimal(rate), false, true);
    }
}
