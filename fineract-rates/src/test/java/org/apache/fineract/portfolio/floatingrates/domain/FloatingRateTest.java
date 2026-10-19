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

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2024, 6, 15);

    @BeforeEach
    void setUp() {
        ThreadLocalContextUtil.setBusinessDates(new HashMap<>(Map.of(BusinessDateType.BUSINESS_DATE, BUSINESS_DATE)));
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.reset();
    }

    @Test
    void constructor_linksEveryPeriodBackToTheRate() {
        final FloatingRatePeriod first = period("2020-01-01", "5");
        final FloatingRatePeriod second = period("2021-01-01", "6");

        final FloatingRate rate = new FloatingRate("Prime", true, false, new ArrayList<>(List.of(first, second)));

        assertThat(rate.getName()).isEqualTo("Prime");
        assertThat(rate.isBaseLendingRate()).isTrue();
        assertThat(rate.isActive()).isFalse();
        assertThat(rate.getFloatingRatePeriods()).containsExactly(first, second);
        assertThat(first.getFloatingRate()).isSameAs(rate);
        assertThat(second.getFloatingRate()).isSameAs(rate);
    }

    @Test
    void constructor_acceptsMissingPeriods() {
        final FloatingRate rate = new FloatingRate("Prime", false, true, null);

        assertThat(rate.getFloatingRatePeriods()).isNull();
    }

    @Test
    void setters_updateNameAndFlags() {
        final FloatingRate rate = new FloatingRate();

        rate.setName("Renamed");
        rate.setBaseLendingRate(true);
        rate.setActive(true);

        assertThat(rate.getName()).isEqualTo("Renamed");
        assertThat(rate.isBaseLendingRate()).isTrue();
        assertThat(rate.isActive()).isTrue();
    }

    @Test
    void fixedRate_startBetweenPeriods_usesPeriodInEffectOnStartDate() {
        assertThat(fromDates(threePeriodRate().fetchInterestRates(dto(false, "2021-06-01")))).containsExactly(date("2021-01-01"));
    }

    @Test
    void fixedRate_startOnPeriodFromDate_usesThatPeriod() {
        assertThat(fromDates(threePeriodRate().fetchInterestRates(dto(false, "2021-01-01")))).containsExactly(date("2021-01-01"));
    }

    @Test
    void fixedRate_startDayBeforePeriodFromDate_usesPreviousPeriod() {
        assertThat(fromDates(threePeriodRate().fetchInterestRates(dto(false, "2020-12-31")))).containsExactly(date("2020-01-01"));
    }

    @Test
    void fixedRate_startBeforeFirstPeriod_fallsBackToFirstPeriod() {
        assertThat(fromDates(threePeriodRate().fetchInterestRates(dto(false, "2019-01-01")))).containsExactly(date("2020-01-01"));
    }

    @Test
    void fixedRate_startAfterLastPeriod_usesLastPeriod() {
        assertThat(fromDates(threePeriodRate().fetchInterestRates(dto(false, "2030-01-01")))).containsExactly(date("2022-01-01"));
    }

    @Test
    void floatingRate_startBetweenPeriods_returnsPeriodInEffectAndAllLaterPeriods() {
        assertThat(fromDates(threePeriodRate().fetchInterestRates(dto(true, "2021-06-01")))).containsExactly(date("2021-01-01"),
                date("2022-01-01"));
    }

    @Test
    void floatingRate_startOnPeriodFromDate_returnsThatPeriodAndAllLaterPeriods() {
        assertThat(fromDates(threePeriodRate().fetchInterestRates(dto(true, "2021-01-01")))).containsExactly(date("2021-01-01"),
                date("2022-01-01"));
    }

    @Test
    void floatingRate_startBeforeFirstPeriod_returnsAllPeriods() {
        assertThat(fromDates(threePeriodRate().fetchInterestRates(dto(true, "2019-01-01")))).containsExactly(date("2020-01-01"),
                date("2021-01-01"), date("2022-01-01"));
    }

    @Test
    void floatingRate_startAfterLastPeriod_returnsOnlyLastPeriod() {
        assertThat(fromDates(threePeriodRate().fetchInterestRates(dto(true, "2030-01-01")))).containsExactly(date("2022-01-01"));
    }

    @Test
    void fixedRate_inactivePeriodsAreSkipped() {
        final FloatingRate rate = rateWith(period("2020-01-01", "5"), inactivePeriod("2021-01-01", "6"), period("2022-01-01", "7"));

        assertThat(fromDates(rate.fetchInterestRates(dto(false, "2021-06-01")))).containsExactly(date("2020-01-01"));
    }

    @Test
    void floatingRate_inactivePeriodsAreSkipped() {
        final FloatingRate rate = rateWith(period("2020-01-01", "5"), inactivePeriod("2021-01-01", "6"), period("2022-01-01", "7"));

        assertThat(fromDates(rate.fetchInterestRates(dto(true, "2020-06-01")))).containsExactly(date("2020-01-01"), date("2022-01-01"));
    }

    @Test
    void noActivePeriods_returnsNoApplicableRates() {
        final FloatingRate rate = rateWith(inactivePeriod("2020-01-01", "5"), inactivePeriod("2021-01-01", "6"));

        assertThat(rate.fetchInterestRates(dto(false, "2021-06-01"))).isEmpty();
        assertThat(rate.fetchInterestRates(dto(true, "2021-06-01"))).isEmpty();
        assertThat(rateWith().fetchInterestRates(dto(true, "2021-06-01"))).isEmpty();
    }

    @Test
    void applicableRate_includesLoanInterestRateDifferential() {
        final FloatingRateDTO dto = new FloatingRateDTO(false, date("2021-06-01"), new BigDecimal("1.5"), List.of());

        final Collection<FloatingRatePeriodData> applicable = threePeriodRate().fetchInterestRates(dto);

        assertThat(applicable).singleElement().satisfies(data -> assertThat(data.getInterestRate()).isEqualByComparingTo("7.5"));
    }

    @Test
    void replaceRatePeriods_deactivatesOnlyFuturePeriodsAndAppendsNewOnes() {
        final FloatingRatePeriod past = period("2024-01-01", "5");
        final FloatingRatePeriod today = period("2024-06-15", "5.5");
        final FloatingRatePeriod future = period("2024-09-01", "6");
        final FloatingRatePeriod farFuture = period("2025-01-01", "6.5");
        final FloatingRate rate = rateWith(past, today, future, farFuture);
        final FloatingRatePeriod replacement = period("2024-10-01", "7");

        rate.replaceRatePeriods(List.of(replacement));

        assertThat(past.isActive()).isTrue();
        assertThat(today.isActive()).isTrue();
        assertThat(future.isActive()).isFalse();
        assertThat(farFuture.isActive()).isFalse();
        assertThat(rate.getFloatingRatePeriods()).containsExactly(past, today, future, farFuture, replacement);
        assertThat(replacement.isActive()).isTrue();
        assertThat(replacement.getFloatingRate()).isSameAs(rate);
    }

    @Test
    void replaceRatePeriods_keepsDeactivatedPeriodsOutOfRateLookup() {
        final FloatingRate rate = rateWith(period("2024-01-01", "5"), period("2024-09-01", "6"));

        rate.replaceRatePeriods(List.of(period("2024-10-01", "7")));

        assertThat(fromDates(rate.fetchInterestRates(dto(true, "2024-07-01")))).containsExactly(date("2024-01-01"), date("2024-10-01"));
    }

    // Likely bug (documented in PR): a rate built without a period list cannot receive periods.
    @Test
    void replaceRatePeriods_onRateWithoutPeriodList_throwsNullPointerException() {
        final FloatingRate rate = new FloatingRate("Prime", false, true, null);
        final List<FloatingRatePeriod> replacement = List.of(period("2024-10-01", "7"));

        assertThatThrownBy(() -> rate.replaceRatePeriods(replacement)).isInstanceOf(NullPointerException.class);
    }

    private static FloatingRate threePeriodRate() {
        return rateWith(period("2020-01-01", "5"), period("2021-01-01", "6"), period("2022-01-01", "7"));
    }

    private static FloatingRate rateWith(final FloatingRatePeriod... periods) {
        return new FloatingRate("Prime", false, true, new ArrayList<>(List.of(periods)));
    }

    private static FloatingRatePeriod period(final String fromDate, final String rate) {
        return new FloatingRatePeriod(date(fromDate), new BigDecimal(rate), false, true);
    }

    private static FloatingRatePeriod inactivePeriod(final String fromDate, final String rate) {
        return new FloatingRatePeriod(date(fromDate), new BigDecimal(rate), false, false);
    }

    private static FloatingRateDTO dto(final boolean isFloatingInterestRate, final String startDate) {
        return new FloatingRateDTO(isFloatingInterestRate, date(startDate), BigDecimal.ZERO, List.of());
    }

    private static List<LocalDate> fromDates(final Collection<FloatingRatePeriodData> periods) {
        return periods.stream().map(FloatingRatePeriodData::getFromDate).toList();
    }

    private static LocalDate date(final String value) {
        return LocalDate.parse(value);
    }
}
