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

class FloatingRateTest {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 3, 15);
    private static final LocalDate JAN = LocalDate.of(2026, 1, 1);
    private static final LocalDate APR = LocalDate.of(2026, 4, 1);
    private static final LocalDate JUL = LocalDate.of(2026, 7, 1);

    @BeforeEach
    void setUp() {
        ThreadLocalContextUtil.setBusinessDates(new HashMap<>(Map.of(BusinessDateType.BUSINESS_DATE, BUSINESS_DATE)));
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.reset();
    }

    @Test
    void constructorLinksEveryPeriodToTheRate() {
        final FloatingRatePeriod p1 = period(JAN, "5", true);
        final FloatingRatePeriod p2 = period(APR, "6", true);

        final FloatingRate rate = new FloatingRate("Prime", true, false, new ArrayList<>(List.of(p1, p2)));

        assertThat(rate.getName()).isEqualTo("Prime");
        assertThat(rate.isBaseLendingRate()).isTrue();
        assertThat(rate.isActive()).isFalse();
        assertThat(rate.getFloatingRatePeriods()).containsExactly(p1, p2);
        assertThat(p1.getFloatingRate()).isSameAs(rate);
        assertThat(p2.getFloatingRate()).isSameAs(rate);
    }

    @Test
    void constructorAcceptsMissingPeriods() {
        assertThat(new FloatingRate("Prime", false, true, null).getFloatingRatePeriods()).isNull();
    }

    @Test
    void replaceRatePeriodsDeactivatesOnlyPeriodsStartingAfterBusinessDate() {
        final FloatingRatePeriod past = period(JAN, "5", true);
        final FloatingRatePeriod startsToday = period(BUSINESS_DATE, "5.5", true);
        final FloatingRatePeriod future = period(APR, "6", true);
        final FloatingRate rate = rate(past, startsToday, future);
        final FloatingRatePeriod replacement = period(JUL, "7", true);

        rate.replaceRatePeriods(List.of(replacement));

        assertThat(past.isActive()).isTrue();
        assertThat(startsToday.isActive()).isTrue();
        assertThat(future.isActive()).isFalse();
        assertThat(rate.getFloatingRatePeriods()).containsExactly(past, startsToday, future, replacement);
        assertThat(replacement.getFloatingRate()).isSameAs(rate);
    }

    @Test
    void replaceRatePeriodsKeepsAlreadyInactiveHistoryInactive() {
        final FloatingRatePeriod inactivePast = period(JAN, "5", false);
        final FloatingRate rate = rate(inactivePast);

        rate.replaceRatePeriods(List.of(period(JUL, "7", true)));

        assertThat(inactivePast.isActive()).isFalse();
    }

    @Test
    void replaceRatePeriodsOnRateBuiltWithoutPeriodsCurrentlyFailsWithNullPointer() {
        final FloatingRate rate = new FloatingRate("Prime", false, true, null);

        assertThatThrownBy(() -> rate.replaceRatePeriods(List.of(period(JUL, "7", true)))).isInstanceOf(NullPointerException.class);
    }

    @Test
    void fixedRateLoanUsesPeriodInEffectOnStartDate() {
        final Collection<FloatingRatePeriodData> rates = threePeriodRate().fetchInterestRates(dto(false, LocalDate.of(2026, 5, 10)));

        assertThat(fromDates(rates)).containsExactly(APR);
        assertThat(rates.iterator().next().getInterestRate()).isEqualByComparingTo("6");
    }

    @Test
    void periodTakesEffectOnItsFromDate() {
        assertThat(fromDates(threePeriodRate().fetchInterestRates(dto(false, APR)))).containsExactly(APR);
    }

    @Test
    void dayBeforeFromDateStillUsesPreviousPeriod() {
        assertThat(fromDates(threePeriodRate().fetchInterestRates(dto(false, APR.minusDays(1))))).containsExactly(JAN);
    }

    @Test
    void fixedRateLoanStartingBeforeFirstPeriodUsesFirstPeriod() {
        assertThat(fromDates(threePeriodRate().fetchInterestRates(dto(false, LocalDate.of(2025, 12, 1))))).containsExactly(JAN);
    }

    @Test
    void fixedRateLoanStartingAfterLastPeriodUsesLastPeriod() {
        assertThat(fromDates(threePeriodRate().fetchInterestRates(dto(false, LocalDate.of(2026, 9, 1))))).containsExactly(JUL);
    }

    @Test
    void floatingRateLoanGetsPeriodInEffectPlusAllLaterPeriods() {
        assertThat(fromDates(threePeriodRate().fetchInterestRates(dto(true, LocalDate.of(2026, 5, 10))))).containsExactly(APR, JUL);
    }

    @Test
    void floatingRateLoanStartingOnFromDateDoesNotIncludeEarlierPeriod() {
        assertThat(fromDates(threePeriodRate().fetchInterestRates(dto(true, APR)))).containsExactly(APR, JUL);
    }

    @Test
    void floatingRateLoanStartingBeforeFirstPeriodGetsAllPeriodsOnce() {
        assertThat(fromDates(threePeriodRate().fetchInterestRates(dto(true, LocalDate.of(2025, 12, 1))))).containsExactly(JAN, APR, JUL);
    }

    @Test
    void floatingRateLoanStartingAfterLastPeriodGetsOnlyLastPeriod() {
        assertThat(fromDates(threePeriodRate().fetchInterestRates(dto(true, LocalDate.of(2026, 9, 1))))).containsExactly(JUL);
    }

    @Test
    void inactivePeriodsAreIgnoredWhenSelectingApplicableRate() {
        final FloatingRate rate = rate(period(JAN, "5", true), period(APR, "6", false), period(JUL, "7", true));

        assertThat(fromDates(rate.fetchInterestRates(dto(false, LocalDate.of(2026, 5, 10))))).containsExactly(JAN);
        assertThat(fromDates(rate.fetchInterestRates(dto(true, LocalDate.of(2026, 5, 10))))).containsExactly(JAN, JUL);
    }

    @Test
    void rateWithoutActivePeriodsHasNoApplicableRates() {
        assertThat(rate(period(JAN, "5", false)).fetchInterestRates(dto(false, APR))).isEmpty();
        assertThat(rate().fetchInterestRates(dto(true, APR))).isEmpty();
    }

    @Test
    void loanDifferentialIsAddedToEveryApplicableRate() {
        final FloatingRateDTO dto = new FloatingRateDTO(true, LocalDate.of(2026, 5, 10), new BigDecimal("1.5"), List.of());

        final List<BigDecimal> rates = threePeriodRate().fetchInterestRates(dto).stream().map(FloatingRatePeriodData::getInterestRate)
                .toList();

        assertThat(rates).usingElementComparator(BigDecimal::compareTo).containsExactly(new BigDecimal("7.5"), new BigDecimal("8.5"));
    }

    @Test
    void periodDifferentialToBaseLendingRateAddsBaseRateEffectiveOnPeriodStart() {
        final FloatingRatePeriod differential = new FloatingRatePeriod(APR, new BigDecimal("1"), true, true);
        final FloatingRate rate = rate(differential);
        final List<FloatingRatePeriodData> blrPeriodsNewestFirst = List.of(blr(LocalDate.of(2026, 6, 1), "4"),
                blr(LocalDate.of(2026, 3, 1), "3"), blr(JAN, "2"));
        final FloatingRateDTO dto = new FloatingRateDTO(false, LocalDate.of(2026, 8, 1), new BigDecimal("0.5"), blrPeriodsNewestFirst);

        final Collection<FloatingRatePeriodData> rates = rate.fetchInterestRates(dto);

        assertThat(rates).singleElement().satisfies(r -> assertThat(r.getInterestRate()).isEqualByComparingTo("4.5"));
    }

    private static FloatingRate threePeriodRate() {
        return rate(period(JAN, "5", true), period(APR, "6", true), period(JUL, "7", true));
    }

    private static FloatingRate rate(final FloatingRatePeriod... periods) {
        return new FloatingRate("Prime", false, true, new ArrayList<>(List.of(periods)));
    }

    private static FloatingRatePeriod period(final LocalDate fromDate, final String rate, final boolean active) {
        return new FloatingRatePeriod(fromDate, new BigDecimal(rate), false, active);
    }

    private static FloatingRatePeriodData blr(final LocalDate fromDate, final String rate) {
        return new FloatingRatePeriodData(null, fromDate, new BigDecimal(rate), false, true);
    }

    private static FloatingRateDTO dto(final boolean floating, final LocalDate startDate) {
        return new FloatingRateDTO(floating, startDate, BigDecimal.ZERO, List.of());
    }

    private static List<LocalDate> fromDates(final Collection<FloatingRatePeriodData> rates) {
        return rates.stream().map(FloatingRatePeriodData::getFromDate).toList();
    }
}
