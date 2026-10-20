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
import java.util.ArrayList;
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

    private StubJdbcTemplate jdbcTemplate;
    private FloatingRatesReadServiceImpl service;

    @BeforeEach
    void setUp() {
        ThreadLocalContextUtil.setTenant(new FineractPlatformTenant(1L, "default", "Default", "UTC", null));
        jdbcTemplate = new StubJdbcTemplate();
        service = new FloatingRatesReadServiceImpl(jdbcTemplate);
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.reset();
    }

    @Test
    void retrieveAllReturnsEveryRateWithoutLoadingPeriods() throws SQLException {
        jdbcTemplate.rateRows.add(rateRow(1L, "Prime", false, true));
        jdbcTemplate.rateRows.add(rateRow(2L, "Old", false, false));

        final List<FloatingRateData> rates = service.retrieveAll();

        assertThat(rates).extracting(FloatingRateData::getName).containsExactly("Prime", "Old");
        assertThat(rates).allSatisfy(r -> assertThat(r.getRatePeriods()).isNull());
        assertThat(jdbcTemplate.sqls).singleElement().satisfies(sql -> assertThat(sql).doesNotContain("where"));
    }

    @Test
    void retrieveAllActiveFiltersOnActiveFlag() throws SQLException {
        jdbcTemplate.rateRows.add(rateRow(1L, "Prime", false, true));

        assertThat(service.retrieveAllActive()).hasSize(1);
        assertThat(jdbcTemplate.sqls.get(0)).contains("where rate.is_active = true");
    }

    @Test
    void retrieveLookupActiveReturnsLightweightActiveRates() throws SQLException {
        final ResultSet rs = mock(ResultSet.class);
        when(rs.getLong("id")).thenReturn(3L);
        when(rs.getString("name")).thenReturn("BLR");
        when(rs.getBoolean("isBaseLendingRate")).thenReturn(true);
        jdbcTemplate.rateRows.add(rs);

        final List<FloatingRateData> rates = service.retrieveLookupActive();

        assertThat(rates).singleElement().satisfies(r -> {
            assertThat(r.getId()).isEqualTo(3L);
            assertThat(r.getIsBaseLendingRate()).isTrue();
            assertThat(r.getIsActive()).isTrue();
            assertThat(r.getCreatedBy()).isNull();
        });
        assertThat(jdbcTemplate.sqls.get(0)).contains("where rate.is_active = true");
    }

    @Test
    void retrieveOneLoadsActivePeriodsNewestFirst() throws SQLException {
        jdbcTemplate.rateRows.add(rateRow(5L, "Prime", false, true));
        final ResultSet periodRow = mock(ResultSet.class);
        when(periodRow.getLong("id")).thenReturn(50L);
        when(periodRow.getDate("fromDate")).thenReturn(Date.valueOf(LocalDate.of(2026, 4, 1)));
        when(periodRow.getBigDecimal("interestRate")).thenReturn(new BigDecimal("6.5"));
        when(periodRow.getBoolean("isDifferentialToBaseLendingRate")).thenReturn(true);
        when(periodRow.getBoolean("isActive")).thenReturn(true);
        when(periodRow.getString("createdBy")).thenReturn("mifos");
        jdbcTemplate.periodRows.add(periodRow);

        final FloatingRateData rate = service.retrieveOne(5L);

        assertThat(rate.getId()).isEqualTo(5L);
        assertThat(rate.getRatePeriods()).singleElement().satisfies(p -> {
            assertThat(p.getId()).isEqualTo(50L);
            assertThat(p.getFromDate()).isEqualTo(LocalDate.of(2026, 4, 1));
            assertThat(p.getInterestRate()).isEqualByComparingTo("6.5");
            assertThat(p.getIsDifferentialToBaseLendingRate()).isTrue();
            assertThat(p.getCreatedBy()).isEqualTo("mifos");
        });
        assertThat(jdbcTemplate.sqls.get(0)).contains("where rate.id = ?");
        assertThat(jdbcTemplate.args.get(0)).containsExactly(5L);
        assertThat(jdbcTemplate.sqls.get(1)).contains("period.is_active = true", "period.floating_rates_id = ?",
                "order by period.from_date desc");
        assertThat(jdbcTemplate.args.get(1)).containsExactly(5L);
    }

    @Test
    void auditTimestampsPreferUtcColumnsAndFallBackToLegacyColumns() throws SQLException {
        final LocalDateTime legacy = LocalDateTime.of(2026, 1, 1, 10, 0);
        final LocalDateTime utc = LocalDateTime.of(2026, 1, 2, 11, 0);
        final ResultSet row = rateRow(5L, "Prime", false, true);
        when(row.getTimestamp("createdOn")).thenReturn(Timestamp.valueOf(legacy));
        when(row.getTimestamp("createdOnUTC")).thenReturn(Timestamp.valueOf(utc));
        when(row.getTimestamp("modifiedOn")).thenReturn(Timestamp.valueOf(legacy));
        jdbcTemplate.rateRows.add(row);
        final ResultSet periodRow = mock(ResultSet.class);
        when(periodRow.getTimestamp("createdOn")).thenReturn(Timestamp.valueOf(legacy));
        when(periodRow.getTimestamp("modifiedOn")).thenReturn(Timestamp.valueOf(legacy));
        when(periodRow.getTimestamp("modifiedOnUTC")).thenReturn(Timestamp.valueOf(utc));
        jdbcTemplate.periodRows.add(periodRow);

        final FloatingRateData rate = service.retrieveOne(5L);

        assertThat(rate.getCreatedOn().toInstant()).isEqualTo(Timestamp.valueOf(utc).toInstant());
        assertThat(rate.getModifiedOn().toInstant()).isEqualTo(Timestamp.valueOf(legacy).toInstant());
        final FloatingRatePeriodData period = rate.getRatePeriods().get(0);
        assertThat(period.getCreatedOn().toInstant()).isEqualTo(Timestamp.valueOf(legacy).toInstant());
        assertThat(period.getModifiedOn().toInstant()).isEqualTo(Timestamp.valueOf(utc).toInstant());
    }

    @Test
    void auditTimestampsUseUtcColumnsWhenAllArePresent() throws SQLException {
        final LocalDateTime legacy = LocalDateTime.of(2026, 1, 1, 10, 0);
        final LocalDateTime utc = LocalDateTime.of(2026, 1, 2, 11, 0);
        final ResultSet row = rateRow(5L, "Prime", false, true);
        when(row.getTimestamp("modifiedOn")).thenReturn(Timestamp.valueOf(legacy));
        when(row.getTimestamp("modifiedOnUTC")).thenReturn(Timestamp.valueOf(utc));
        jdbcTemplate.rateRows.add(row);
        final ResultSet periodRow = mock(ResultSet.class);
        when(periodRow.getTimestamp("createdOn")).thenReturn(Timestamp.valueOf(legacy));
        when(periodRow.getTimestamp("createdOnUTC")).thenReturn(Timestamp.valueOf(utc));
        jdbcTemplate.periodRows.add(periodRow);

        final FloatingRateData rate = service.retrieveOne(5L);

        assertThat(rate.getModifiedOn().toInstant()).isEqualTo(Timestamp.valueOf(utc).toInstant());
        assertThat(rate.getRatePeriods().get(0).getCreatedOn().toInstant()).isEqualTo(Timestamp.valueOf(utc).toInstant());
    }

    @Test
    void retrieveOneThrowsNotFoundForUnknownId() {
        assertThatThrownBy(() -> service.retrieveOne(9L)).isInstanceOfSatisfying(FloatingRateNotFoundException.class,
                e -> assertThat(e.getGlobalisationMessageCode()).isEqualTo("error.msg.floatingrate.id.invalid"));
    }

    @Test
    void retrieveBaseLendingRateSelectsActiveBaseLendingRateWithPeriods() throws SQLException {
        jdbcTemplate.rateRows.add(rateRow(1L, "BLR", true, true));

        final FloatingRateData blr = service.retrieveBaseLendingRate();

        assertThat(blr.getIsBaseLendingRate()).isTrue();
        assertThat(blr.getRatePeriods()).isEmpty();
        assertThat(jdbcTemplate.sqls.get(0)).contains("rate.is_base_lending_rate = true and rate.is_active = true");
    }

    @Test
    void retrieveBaseLendingRateThrowsNotFoundWhenNoneConfigured() {
        assertThatThrownBy(() -> service.retrieveBaseLendingRate()).isInstanceOfSatisfying(FloatingRateNotFoundException.class,
                e -> assertThat(e.getGlobalisationMessageCode()).isEqualTo("error.msg.floatingrate.base.lending.rate.not.found"));
    }

    @Test
    void retrieveInterestRatePeriodsMapsLinkedAndBaseRates() throws SQLException {
        final ResultSet linkedToBlr = mock(ResultSet.class);
        when(linkedToBlr.getDate("linkedrateperiods_from_date")).thenReturn(Date.valueOf(LocalDate.of(2026, 4, 1)));
        when(linkedToBlr.getBigDecimal("linkedrateperiods_interest_rate")).thenReturn(new BigDecimal("1.5"));
        when(linkedToBlr.getBoolean("linkedrateperiods_is_differential_to_base_lending_rate")).thenReturn(true);
        when(linkedToBlr.getDate("baserate_from_date")).thenReturn(Date.valueOf(LocalDate.of(2026, 3, 1)));
        when(linkedToBlr.getBigDecimal("baserate_interest_rate")).thenReturn(new BigDecimal("4"));
        final ResultSet absolute = mock(ResultSet.class);
        when(absolute.getDate("linkedrateperiods_from_date")).thenReturn(Date.valueOf(LocalDate.of(2026, 1, 1)));
        when(absolute.getBigDecimal("linkedrateperiods_interest_rate")).thenReturn(new BigDecimal("6"));
        jdbcTemplate.interestRows.add(linkedToBlr);
        jdbcTemplate.interestRows.add(absolute);

        final List<InterestRatePeriodData> periods = service.retrieveInterestRatePeriods(42L);

        assertThat(periods).hasSize(2);
        assertThat(periods.get(0).getFromDate()).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(periods.get(0).isDifferentialToBLR()).isTrue();
        assertThat(periods.get(0).getBlrFromDate()).isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(periods.get(0).getBlrInterestRate()).isEqualByComparingTo("4");
        assertThat(periods.get(1).isDifferentialToBLR()).isFalse();
        assertThat(periods.get(1).getBlrFromDate()).isNull();
        assertThat(periods.get(1).getBlrInterestRate()).isNull();
        assertThat(jdbcTemplate.args.get(0)).containsExactly(42L);
        assertThat(jdbcTemplate.sqls.get(0)).contains("lp.id = ?", "order by linkedratePeriods_from_date desc");
    }

    @Test
    void retrieveInterestRatePeriodsMapsMissingLinkedPeriodDate() throws SQLException {
        jdbcTemplate.interestRows.add(mock(ResultSet.class));

        assertThat(service.retrieveInterestRatePeriods(42L)).singleElement().satisfies(p -> assertThat(p.getFromDate()).isNull());
    }

    @Test
    void retrieveInterestRatePeriodsTranslatesEmptyResultToNotFound() {
        jdbcTemplate.throwEmptyOnQuery = true;

        assertThatThrownBy(() -> service.retrieveInterestRatePeriods(42L)).isInstanceOfSatisfying(FloatingRateNotFoundException.class,
                e -> assertThat(e.getGlobalisationMessageCode()).isEqualTo("error.msg.floatingrate.not.found.for.product"));
    }

    private static ResultSet rateRow(final Long id, final String name, final boolean blr, final boolean active) throws SQLException {
        final ResultSet rs = mock(ResultSet.class);
        when(rs.getLong("id")).thenReturn(id);
        when(rs.getString("name")).thenReturn(name);
        when(rs.getBoolean("isBaseLendingRate")).thenReturn(blr);
        when(rs.getBoolean("isActive")).thenReturn(active);
        return rs;
    }

    private static final class StubJdbcTemplate extends JdbcTemplate {

        private final List<ResultSet> rateRows = new ArrayList<>();
        private final List<ResultSet> periodRows = new ArrayList<>();
        private final List<ResultSet> interestRows = new ArrayList<>();
        private final List<String> sqls = new ArrayList<>();
        private final List<Object[]> args = new ArrayList<>();
        private boolean throwEmptyOnQuery;

        @Override
        public <T> List<T> query(final String sql, final RowMapper<T> rowMapper) {
            return map(sql, rowMapper, new Object[0]);
        }

        @Override
        public <T> List<T> query(final String sql, final RowMapper<T> rowMapper, final Object... params) {
            if (throwEmptyOnQuery) {
                throw new EmptyResultDataAccessException(1);
            }
            return map(sql, rowMapper, params);
        }

        @Override
        public <T> T queryForObject(final String sql, final RowMapper<T> rowMapper) {
            return single(map(sql, rowMapper, new Object[0]));
        }

        @Override
        public <T> T queryForObject(final String sql, final RowMapper<T> rowMapper, final Object... params) {
            return single(map(sql, rowMapper, params));
        }

        private <T> List<T> map(final String sql, final RowMapper<T> rowMapper, final Object[] params) {
            sqls.add(sql);
            args.add(params);
            final List<T> result = new ArrayList<>();
            final List<ResultSet> rows = rowsFor(sql);
            for (int i = 0; i < rows.size(); i++) {
                try {
                    result.add(rowMapper.mapRow(rows.get(i), i));
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            }
            return result;
        }

        private List<ResultSet> rowsFor(final String sql) {
            if (sql.contains("m_product_loan")) {
                return interestRows;
            }
            if (sql.contains("m_floating_rates_periods as period")) {
                return periodRows;
            }
            return rateRows;
        }

        private static <T> T single(final List<T> results) {
            if (results.isEmpty()) {
                throw new EmptyResultDataAccessException(1);
            }
            return results.get(0);
        }
    }
}
