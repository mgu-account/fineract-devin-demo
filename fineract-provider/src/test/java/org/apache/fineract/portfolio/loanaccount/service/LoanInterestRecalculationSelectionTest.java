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
package org.apache.fineract.portfolio.loanaccount.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.time.LocalDate;
import java.util.HashMap;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Runs the "Recalculate Interest For Loans" selection SQL against a real PostgreSQL to check which loans are picked up
 * because their floating rate changed. Only the tables and columns the query reads are created. Requires Docker; the
 * class is skipped when Docker is unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
class LoanInterestRecalculationSelectionTest {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2024, 3, 21);
    private static final LocalDate YESTERDAY = BUSINESS_DATE.minusDays(1);
    private static final LocalDate LONG_AGO = LocalDate.of(2024, 2, 1);
    private static final int ACTIVE = 300;

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcTemplate jdbcTemplate;
    private LoanReadPlatformServiceImpl underTest;

    @BeforeAll
    static void createSchema() {
        jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        jdbcTemplate.execute("""
                CREATE TABLE m_office (id BIGINT PRIMARY KEY, hierarchy VARCHAR(100));
                CREATE TABLE m_client (id BIGINT PRIMARY KEY, office_id BIGINT);
                CREATE TABLE m_loan (id BIGINT PRIMARY KEY, client_id BIGINT, product_id BIGINT, loan_status_id INT,
                    is_npa BOOLEAN, is_charged_off BOOLEAN, is_floating_interest_rate BOOLEAN,
                    interest_recalculation_enabled BOOLEAN, interest_recalcualated_on DATE);
                CREATE TABLE m_loan_repayment_schedule (id BIGSERIAL PRIMARY KEY, loan_id BIGINT, duedate DATE, completed_derived BOOLEAN);
                CREATE TABLE m_loan_disbursement_detail (id BIGSERIAL PRIMARY KEY, loan_id BIGINT, expected_disburse_date DATE,
                    disbursedon_date DATE, is_reversed BOOLEAN);
                CREATE TABLE m_loan_recalculation_details (id BIGSERIAL PRIMARY KEY, loan_id BIGINT, disallow_interest_calc_on_past_due BOOLEAN);
                CREATE TABLE m_loan_reschedule_request (id BIGSERIAL PRIMARY KEY, loan_id BIGINT);
                CREATE TABLE m_product_loan_floating_rates (loan_product_id BIGINT, floating_rates_id BIGINT);
                CREATE TABLE m_floating_rates (id BIGINT PRIMARY KEY, is_base_lending_rate BOOLEAN, is_active BOOLEAN);
                CREATE TABLE m_floating_rates_periods (id BIGSERIAL PRIMARY KEY, floating_rates_id BIGINT, from_date DATE,
                    is_differential_to_base_lending_rate BOOLEAN, is_active BOOLEAN, created_date TIMESTAMP, created_on_business_date DATE);
                INSERT INTO m_office VALUES (1, '.');
                INSERT INTO m_client VALUES (1, 1);
                """);
    }

    @BeforeEach
    void setUp() throws Exception {
        jdbcTemplate.execute("""
                TRUNCATE m_loan, m_loan_repayment_schedule, m_loan_disbursement_detail, m_loan_recalculation_details,
                    m_loan_reschedule_request, m_product_loan_floating_rates, m_floating_rates, m_floating_rates_periods
                """);
        HashMap<BusinessDateType, LocalDate> businessDates = new HashMap<>();
        businessDates.put(BusinessDateType.BUSINESS_DATE, BUSINESS_DATE);
        ThreadLocalContextUtil.setBusinessDates(businessDates);
        underTest = newServiceWith(jdbcTemplate);
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.reset();
    }

    @Test
    void selectsOnlyFloatingLoansWhoseRateGainedAPeriodSinceYesterday() {
        floatingRate(10, false);
        period(10, LocalDate.of(2024, 4, 1), false, YESTERDAY);
        floatingRate(20, false);
        period(20, LocalDate.of(2024, 3, 1), false, LONG_AGO);
        product(100, 10);
        product(200, 20);

        loan(1, 100, true);
        loan(2, 200, true);
        loan(3, 100, false);
        loan(4, 100, true);
        jdbcTemplate.update("INSERT INTO m_loan_reschedule_request (loan_id) VALUES (4)");
        loan(5, 100, true);
        jdbcTemplate.update("UPDATE m_loan SET is_npa = TRUE WHERE id = 5");
        loan(6, 100, true);
        jdbcTemplate.update("UPDATE m_loan SET is_charged_off = TRUE WHERE id = 6");

        assertThat(underTest.fetchLoansForInterestRecalculation(100, 0L, ".%")).containsExactly(1L);
        assertThat(underTest.fetchLoansForInterestRecalculation()).containsExactly(1L);
    }

    @Test
    void ignoresInactivePeriodsAndInactiveRates() {
        floatingRate(10, false);
        jdbcTemplate.update("INSERT INTO m_floating_rates_periods (floating_rates_id, from_date, is_differential_to_base_lending_rate,"
                + " is_active, created_on_business_date) VALUES (10, '2024-04-01', FALSE, FALSE, ?)", YESTERDAY);
        jdbcTemplate.update("INSERT INTO m_floating_rates VALUES (30, FALSE, FALSE)");
        period(30, LocalDate.of(2024, 4, 1), false, YESTERDAY);
        product(100, 10);
        product(300, 30);
        loan(1, 100, true);
        loan(2, 300, true);

        assertThat(underTest.fetchLoansForInterestRecalculation(100, 0L, ".%")).isEmpty();
        assertThat(underTest.fetchLoansForInterestRecalculation()).isEmpty();
    }

    @Test
    void legacyCreatedDateNoLongerDrivesSelection() {
        floatingRate(10, false);
        jdbcTemplate.update("INSERT INTO m_floating_rates_periods (floating_rates_id, from_date, is_differential_to_base_lending_rate,"
                + " is_active, created_date) VALUES (10, '2024-04-01', FALSE, TRUE, ?)", YESTERDAY.atStartOfDay());
        product(100, 10);
        loan(1, 100, true);

        assertThat(underTest.fetchLoansForInterestRecalculation(100, 0L, ".%")).isEmpty();
    }

    @Test
    void baseLendingRateChangeSelectsLoansOnDifferentialPeriods() {
        floatingRate(1, true);
        period(1, LocalDate.of(2024, 3, 25), false, YESTERDAY);
        floatingRate(10, false);
        period(10, LocalDate.of(2024, 4, 1), true, LONG_AGO);
        floatingRate(20, false);
        period(20, LocalDate.of(2024, 4, 1), false, LONG_AGO);
        product(100, 10);
        product(200, 20);
        loan(1, 100, true);
        loan(2, 200, true);

        assertThat(underTest.fetchLoansForInterestRecalculation(100, 0L, ".%")).containsExactly(1L);
        assertThat(underTest.fetchLoansForInterestRecalculation()).containsExactly(1L);
    }

    private static void floatingRate(long id, boolean isBaseLendingRate) {
        jdbcTemplate.update("INSERT INTO m_floating_rates VALUES (?, ?, TRUE)", id, isBaseLendingRate);
    }

    private static void period(long rateId, LocalDate fromDate, boolean differential, LocalDate createdOnBusinessDate) {
        jdbcTemplate.update("INSERT INTO m_floating_rates_periods (floating_rates_id, from_date, is_differential_to_base_lending_rate,"
                + " is_active, created_on_business_date) VALUES (?, ?, ?, TRUE, ?)", rateId, fromDate, differential, createdOnBusinessDate);
    }

    private static void product(long productId, long rateId) {
        jdbcTemplate.update("INSERT INTO m_product_loan_floating_rates VALUES (?, ?)", productId, rateId);
    }

    /** An active, up-to-date loan: nothing overdue, so only the floating-rate branch can select it. */
    private static void loan(long id, long productId, boolean isFloating) {
        jdbcTemplate.update("INSERT INTO m_loan VALUES (?, 1, ?, ?, FALSE, FALSE, ?, TRUE, NULL)", id, productId, ACTIVE, isFloating);
        jdbcTemplate.update("INSERT INTO m_loan_repayment_schedule (loan_id, duedate, completed_derived) VALUES (?, '2024-04-15', FALSE)",
                id);
        jdbcTemplate.update("INSERT INTO m_loan_recalculation_details (loan_id, disallow_interest_calc_on_past_due) VALUES (?, FALSE)", id);
    }

    private static LoanReadPlatformServiceImpl newServiceWith(JdbcTemplate jdbcTemplate) throws Exception {
        Constructor<?> constructor = LoanReadPlatformServiceImpl.class.getDeclaredConstructors()[0];
        Object[] args = new Object[constructor.getParameterCount()];
        Class<?>[] types = constructor.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            args[i] = types[i] == JdbcTemplate.class ? jdbcTemplate : null;
        }
        return (LoanReadPlatformServiceImpl) constructor.newInstance(args);
    }
}
