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
package org.apache.fineract.portfolio.floatingrates.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.portfolio.floatingrates.data.FloatingRateData;
import org.apache.fineract.portfolio.floatingrates.data.FloatingRatePeriodData;
import org.apache.fineract.portfolio.floatingrates.data.InterestRatePeriodData;
import org.apache.fineract.portfolio.floatingrates.exception.FloatingRateNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class FloatingRatesReadServiceImplTest {

    private static final Timestamp LOCAL_CREATED = Timestamp.valueOf(LocalDateTime.of(2024, 1, 1, 9, 0));
    private static final Timestamp UTC_CREATED = Timestamp.valueOf(LocalDateTime.of(2024, 1, 2, 9, 0));
    private static final Timestamp LOCAL_MODIFIED = Timestamp.valueOf(LocalDateTime.of(2024, 2, 1, 9, 0));
    private static final Timestamp UTC_MODIFIED = Timestamp.valueOf(LocalDateTime.of(2024, 2, 2, 9, 0));

    private RecordingJdbcTemplate jdbcTemplate;
    private FloatingRatesReadServiceImpl service;

    @BeforeEach
    void setUp() {
        ThreadLocalContextUtil.setTenant(new FineractPlatformTenant(1L, "default", "Default", "UTC", null));
        jdbcTemplate = new RecordingJdbcTemplate();
        service = new FloatingRatesReadServiceImpl(jdbcTemplate);
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.reset();
    }

    @Test
    void retrieveAll_mapsEveryRateWithoutLoadingPeriods() throws SQLException {
        jdbcTemplate.respond(rateRow(1L, "Prime", true, true, UTC_CREATED, UTC_MODIFIED), rateRow(2L, "Legacy", false, false, null, null));

        final List<FloatingRateData> rates = service.retrieveAll();

        assertThat(rates).extracting(FloatingRateData::getId, FloatingRateData::getName, FloatingRateData::getIsBaseLendingRate,
                FloatingRateData::getIsActive).containsExactly(org.assertj.core.groups.Tuple.tuple(1L, "Prime", true, true),
                        org.assertj.core.groups.Tuple.tuple(2L, "Legacy", false, false));
        assertThat(rates).allSatisfy(rate -> assertThat(rate.getRatePeriods()).isNull());
        assertThat(jdbcTemplate.sqls).hasSize(1);
        assertThat(jdbcTemplate.sqls.get(0)).startsWith("select rate.id as id").doesNotContainIgnoringCase("where");
    }

    @Test
    void auditFields_preferUtcColumnsOverLegacyLocalColumns() throws SQLException {
        jdbcTemplate.respond(rateRow(1L, "Prime", false, true, UTC_CREATED, UTC_MODIFIED));

        final FloatingRateData rate = service.retrieveAll().get(0);

        assertThat(rate.getCreatedBy()).isEqualTo("creator");
        assertThat(rate.getModifiedBy()).isEqualTo("modifier");
        assertThat(rate.getCreatedOn().toInstant()).isEqualTo(UTC_CREATED.toInstant());
        assertThat(rate.getModifiedOn().toInstant()).isEqualTo(UTC_MODIFIED.toInstant());
    }

    @Test
    void auditFields_fallBackToLegacyLocalColumnsWhenUtcMissing() throws SQLException {
        jdbcTemplate.respond(rateRow(1L, "Prime", false, true, null, null));

        final FloatingRateData rate = service.retrieveAll().get(0);

        assertThat(rate.getCreatedOn().toInstant()).isEqualTo(LOCAL_CREATED.toInstant());
        assertThat(rate.getModifiedOn().toInstant()).isEqualTo(LOCAL_MODIFIED.toInstant());
    }

    @Test
    void retrieveAllActive_filtersOnActiveRates() throws SQLException {
        jdbcTemplate.respond(rateRow(1L, "Prime", false, true, null, null));

        assertThat(service.retrieveAllActive()).hasSize(1);
        assertThat(jdbcTemplate.sqls.get(0)).contains("where rate.is_active = true");
    }

    @Test
    void retrieveLookupActive_returnsOnlyIdNameAndBaseLendingFlagOfActiveRates() throws SQLException {
        final ResultSet row = mock(ResultSet.class);
        when(row.getLong("id")).thenReturn(3L);
        when(row.getString("name")).thenReturn("Prime");
        when(row.getBoolean("isBaseLendingRate")).thenReturn(true);
        jdbcTemplate.respond(row);

        final FloatingRateData rate = service.retrieveLookupActive().get(0);

        assertThat(rate.getId()).isEqualTo(3L);
        assertThat(rate.getName()).isEqualTo("Prime");
        assertThat(rate.getIsBaseLendingRate()).isTrue();
        assertThat(rate.getIsActive()).isTrue();
        assertThat(rate.getCreatedBy()).isNull();
        assertThat(rate.getRatePeriods()).isNull();
        assertThat(jdbcTemplate.sqls.get(0)).contains("where rate.is_active = true").doesNotContain("m_appuser");
    }

    @Test
    void retrieveOne_loadsActivePeriodsNewestFirst() throws SQLException {
        jdbcTemplate.respond(rateRow(5L, "Prime", false, true, null, null));
        jdbcTemplate.respond(periodRow(11L, LocalDate.of(2024, 9, 1), "6", true, UTC_CREATED, UTC_MODIFIED),
                periodRow(10L, LocalDate.of(2024, 1, 1), "5", false, null, null));

        final FloatingRateData rate = service.retrieveOne(5L);

        assertThat(rate.getId()).isEqualTo(5L);
        assertThat(rate.getRatePeriods()).extracting(FloatingRatePeriodData::getId).containsExactly(11L, 10L);
        final FloatingRatePeriodData newest = rate.getRatePeriods().get(0);
        assertThat(newest.getFromDate()).isEqualTo(LocalDate.of(2024, 9, 1));
        assertThat(newest.getInterestRate()).isEqualByComparingTo("6");
        assertThat(newest.getIsDifferentialToBaseLendingRate()).isTrue();
        assertThat(newest.getIsActive()).isTrue();
        assertThat(newest.getCreatedBy()).isEqualTo("creator");
        assertThat(newest.getCreatedOn().toInstant()).isEqualTo(UTC_CREATED.toInstant());
        assertThat(newest.getModifiedOn().toInstant()).isEqualTo(UTC_MODIFIED.toInstant());
        final FloatingRatePeriodData oldest = rate.getRatePeriods().get(1);
        assertThat(oldest.getCreatedOn().toInstant()).isEqualTo(LOCAL_CREATED.toInstant());
        assertThat(oldest.getModifiedOn().toInstant()).isEqualTo(LOCAL_MODIFIED.toInstant());

        assertThat(jdbcTemplate.sqls.get(0)).contains("where rate.id = ?");
        assertThat(jdbcTemplate.args.get(0)).containsExactly(5L);
        assertThat(jdbcTemplate.sqls.get(1)).contains("period.is_active = true and period.floating_rates_id = ?")
                .contains("order by period.from_date desc");
        assertThat(jdbcTemplate.args.get(1)).containsExactly(5L);
    }

    @Test
    void retrieveOne_unknownId_throwsNotFound() {
        assertThatThrownBy(() -> service.retrieveOne(404L)).isInstanceOf(FloatingRateNotFoundException.class)
                .extracting("globalisationMessageCode").isEqualTo("error.msg.floatingrate.id.invalid");
    }

    @Test
    void retrieveBaseLendingRate_readsTheActiveBaseLendingRateWithItsPeriods() throws SQLException {
        jdbcTemplate.respond(rateRow(1L, "BLR", true, true, null, null));
        jdbcTemplate.respond(periodRow(20L, LocalDate.of(2024, 3, 1), "4", false, null, null));

        final FloatingRateData rate = service.retrieveBaseLendingRate();

        assertThat(rate.getIsBaseLendingRate()).isTrue();
        assertThat(rate.getRatePeriods()).extracting(FloatingRatePeriodData::getId).containsExactly(20L);
        assertThat(jdbcTemplate.sqls.get(0)).contains("where rate.is_base_lending_rate = true and rate.is_active = true");
    }

    @Test
    void retrieveBaseLendingRate_missing_throwsNotFound() {
        assertThatThrownBy(() -> service.retrieveBaseLendingRate()).isInstanceOf(FloatingRateNotFoundException.class)
                .extracting("globalisationMessageCode").isEqualTo("error.msg.floatingrate.base.lending.rate.not.found");
    }

    @Test
    void retrieveInterestRatePeriods_mapsLinkedRateAndBaseRateColumns() throws SQLException {
        final ResultSet row = mock(ResultSet.class);
        when(row.getDate("linkedrateperiods_from_date")).thenReturn(Date.valueOf(LocalDate.of(2024, 9, 1)));
        when(row.getBigDecimal("linkedrateperiods_interest_rate")).thenReturn(new BigDecimal("2"));
        when(row.getBoolean("linkedrateperiods_is_differential_to_base_lending_rate")).thenReturn(true);
        when(row.getDate("baserate_from_date")).thenReturn(Date.valueOf(LocalDate.of(2024, 3, 1)));
        when(row.getBigDecimal("baserate_interest_rate")).thenReturn(new BigDecimal("4"));
        jdbcTemplate.respond(row);

        final InterestRatePeriodData period = service.retrieveInterestRatePeriods(8L).get(0);

        assertThat(period.getFromDate()).isEqualTo(LocalDate.of(2024, 9, 1));
        assertThat(period.getInterestRate()).isEqualByComparingTo("2");
        assertThat(period.isDifferentialToBLR()).isTrue();
        assertThat(period.getBlrFromDate()).isEqualTo(LocalDate.of(2024, 3, 1));
        assertThat(period.getBlrInterestRate()).isEqualByComparingTo("4");
        assertThat(jdbcTemplate.sqls.get(0)).contains("lp.id = ?").contains("order by linkedratePeriods_from_date desc");
        assertThat(jdbcTemplate.args.get(0)).containsExactly(8L);
    }

    @Test
    void retrieveInterestRatePeriods_missingDates_mapToNull() throws SQLException {
        jdbcTemplate.respond(mock(ResultSet.class));

        final InterestRatePeriodData period = service.retrieveInterestRatePeriods(8L).get(0);

        assertThat(period.getFromDate()).isNull();
        assertThat(period.getBlrFromDate()).isNull();
        assertThat(period.isDifferentialToBLR()).isFalse();
    }

    @Test
    void retrieveInterestRatePeriods_productWithoutFloatingRate_returnsEmptyList() {
        assertThat(service.retrieveInterestRatePeriods(8L)).isEmpty();
    }

    @Test
    void retrieveInterestRatePeriods_emptyResultFailure_isReportedAsNotFoundForProduct() {
        jdbcTemplate.failure = new EmptyResultDataAccessException(1);

        assertThatThrownBy(() -> service.retrieveInterestRatePeriods(8L)).isInstanceOf(FloatingRateNotFoundException.class)
                .extracting("globalisationMessageCode").isEqualTo("error.msg.floatingrate.not.found.for.product");
    }

    private static ResultSet rateRow(final long id, final String name, final boolean isBaseLendingRate, final boolean isActive,
            final Timestamp createdOnUtc, final Timestamp modifiedOnUtc) throws SQLException {
        final ResultSet row = mock(ResultSet.class);
        when(row.getLong("id")).thenReturn(id);
        when(row.getString("name")).thenReturn(name);
        when(row.getBoolean("isBaseLendingRate")).thenReturn(isBaseLendingRate);
        when(row.getBoolean("isActive")).thenReturn(isActive);
        stubAudit(row, createdOnUtc, modifiedOnUtc);
        return row;
    }

    private static ResultSet periodRow(final long id, final LocalDate fromDate, final String rate, final boolean differential,
            final Timestamp createdOnUtc, final Timestamp modifiedOnUtc) throws SQLException {
        final ResultSet row = mock(ResultSet.class);
        when(row.getLong("id")).thenReturn(id);
        when(row.getDate("fromDate")).thenReturn(Date.valueOf(fromDate));
        when(row.getBigDecimal("interestRate")).thenReturn(new BigDecimal(rate));
        when(row.getBoolean("isDifferentialToBaseLendingRate")).thenReturn(differential);
        when(row.getBoolean("isActive")).thenReturn(true);
        stubAudit(row, createdOnUtc, modifiedOnUtc);
        return row;
    }

    private static void stubAudit(final ResultSet row, final Timestamp createdOnUtc, final Timestamp modifiedOnUtc) throws SQLException {
        when(row.getString("createdBy")).thenReturn("creator");
        when(row.getString("modifiedBy")).thenReturn("modifier");
        when(row.getTimestamp("createdOn")).thenReturn(LOCAL_CREATED);
        when(row.getTimestamp("createdOnUTC")).thenReturn(createdOnUtc);
        when(row.getTimestamp("modifiedOn")).thenReturn(LOCAL_MODIFIED);
        when(row.getTimestamp("modifiedOnUTC")).thenReturn(modifiedOnUtc);
    }

    /**
     * Serves queued result sets in call order and records every SQL statement with its arguments.
     */
    private static final class RecordingJdbcTemplate extends JdbcTemplate {

        private final Deque<List<ResultSet>> responses = new ArrayDeque<>();
        private final List<String> sqls = new ArrayList<>();
        private final List<Object[]> args = new ArrayList<>();
        private RuntimeException failure;

        void respond(final ResultSet... rows) {
            responses.add(List.of(rows));
        }

        @Override
        public <T> List<T> query(final String sql, final RowMapper<T> rowMapper) {
            return run(sql, rowMapper, new Object[0]);
        }

        @Override
        public <T> List<T> query(final String sql, final RowMapper<T> rowMapper, final Object... queryArgs) {
            return run(sql, rowMapper, queryArgs);
        }

        @Override
        public <T> T queryForObject(final String sql, final RowMapper<T> rowMapper) {
            return single(run(sql, rowMapper, new Object[0]));
        }

        @Override
        public <T> T queryForObject(final String sql, final RowMapper<T> rowMapper, final Object... queryArgs) {
            return single(run(sql, rowMapper, queryArgs));
        }

        private <T> List<T> run(final String sql, final RowMapper<T> rowMapper, final Object[] queryArgs) {
            sqls.add(sql);
            args.add(queryArgs);
            if (failure != null) {
                throw failure;
            }
            final List<ResultSet> rows = responses.isEmpty() ? List.of() : responses.poll();
            final List<T> mapped = new ArrayList<>();
            try {
                for (int i = 0; i < rows.size(); i++) {
                    mapped.add(rowMapper.mapRow(rows.get(i), i));
                }
            } catch (final SQLException e) {
                throw new IllegalStateException(e);
            }
            return mapped;
        }

        private static <T> T single(final List<T> results) {
            if (results.size() != 1) {
                throw new EmptyResultDataAccessException(1);
            }
            return results.get(0);
        }
    }
}
