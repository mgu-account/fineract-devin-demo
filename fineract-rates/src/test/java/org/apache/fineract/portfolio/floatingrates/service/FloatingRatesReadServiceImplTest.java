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

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.portfolio.floatingrates.data.FloatingRateData;
import org.apache.fineract.portfolio.floatingrates.data.InterestRatePeriodData;
import org.apache.fineract.portfolio.floatingrates.exception.FloatingRateNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

public class FloatingRatesReadServiceImplTest {

    private static final String RATE_TABLE = "FROM m_floating_rates as rate";
    private static final String PERIOD_TABLE = "FROM m_floating_rates_periods as period";
    private static final String PRODUCT_TABLE = "from m_product_loan as lp";
    private static final LocalDateTime LEGACY_CREATED = LocalDateTime.of(2024, 1, 1, 10, 0);
    private static final LocalDateTime UTC_CREATED = LocalDateTime.of(2024, 1, 2, 11, 0);
    private static final LocalDateTime LEGACY_MODIFIED = LocalDateTime.of(2024, 2, 1, 10, 0);
    private static final LocalDateTime UTC_MODIFIED = LocalDateTime.of(2024, 2, 2, 11, 0);

    private FakeJdbcTemplate jdbcTemplate;
    private FloatingRatesReadServiceImpl service;

    @BeforeEach
    public void setUp() {
        ThreadLocalContextUtil.setTenant(new FineractPlatformTenant(1L, "default", "Default", "UTC", null));
        jdbcTemplate = new FakeJdbcTemplate();
        service = new FloatingRatesReadServiceImpl(jdbcTemplate);
    }

    @AfterEach
    public void tearDown() {
        ThreadLocalContextUtil.reset();
    }

    @Test
    public void retrieveAllMapsEveryRateWithoutLoadingRatePeriods() {
        jdbcTemplate.rows(RATE_TABLE, rateRow(1L, "BLR", true, true), rateRow(2L, "Prime", false, false));

        final List<FloatingRateData> rates = service.retrieveAll();

        assertThat(rates).extracting(FloatingRateData::getId).containsExactly(1L, 2L);
        assertThat(rates).extracting(FloatingRateData::getName).containsExactly("BLR", "Prime");
        assertThat(rates).extracting(FloatingRateData::getIsBaseLendingRate).containsExactly(true, false);
        assertThat(rates).extracting(FloatingRateData::getIsActive).containsExactly(true, false);
        assertThat(rates).extracting(FloatingRateData::getRatePeriods).containsOnlyNulls();
        assertThat(jdbcTemplate.sqls).hasSize(1);
        assertThat(jdbcTemplate.sqls.get(0)).startsWith("select rate.id as id").doesNotContain("where");
    }

    @Test
    public void auditTimestampsPreferUtcColumnsOverLegacyColumns() {
        final Map<String, Object> row = rateValues(1L, "Prime", false, true);
        row.put("createdBy", "mifos");
        row.put("modifiedBy", "admin");
        row.put("createdOn", Timestamp.valueOf(LEGACY_CREATED));
        row.put("createdOnUTC", Timestamp.valueOf(UTC_CREATED));
        row.put("modifiedOn", Timestamp.valueOf(LEGACY_MODIFIED));
        row.put("modifiedOnUTC", Timestamp.valueOf(UTC_MODIFIED));
        jdbcTemplate.rows(RATE_TABLE, resultSet(row));

        final FloatingRateData rate = service.retrieveAll().get(0);

        assertThat(rate.getCreatedBy()).isEqualTo("mifos");
        assertThat(rate.getModifiedBy()).isEqualTo("admin");
        assertThat(rate.getCreatedOn().toInstant()).isEqualTo(Timestamp.valueOf(UTC_CREATED).toInstant());
        assertThat(rate.getModifiedOn().toInstant()).isEqualTo(Timestamp.valueOf(UTC_MODIFIED).toInstant());
    }

    @Test
    public void auditTimestampsFallBackToLegacyColumnsWhenUtcColumnsAreEmpty() {
        final Map<String, Object> row = rateValues(1L, "Prime", false, true);
        row.put("createdOn", Timestamp.valueOf(LEGACY_CREATED));
        row.put("modifiedOn", Timestamp.valueOf(LEGACY_MODIFIED));
        jdbcTemplate.rows(RATE_TABLE, resultSet(row));

        final FloatingRateData rate = service.retrieveAll().get(0);

        assertThat(rate.getCreatedOn().toInstant()).isEqualTo(Timestamp.valueOf(LEGACY_CREATED).toInstant());
        assertThat(rate.getModifiedOn().toInstant()).isEqualTo(Timestamp.valueOf(LEGACY_MODIFIED).toInstant());
    }

    @Test
    public void retrieveAllActiveFiltersOnActiveFlag() {
        jdbcTemplate.rows(RATE_TABLE, rateRow(1L, "BLR", true, true));

        assertThat(service.retrieveAllActive()).extracting(FloatingRateData::getName).containsExactly("BLR");
        assertThat(jdbcTemplate.sqls).singleElement().satisfies(sql -> assertThat(sql).contains("where rate.is_active = true"));
    }

    @Test
    public void retrieveLookupActiveReturnsLightweightActiveRates() {
        jdbcTemplate.rows(RATE_TABLE, rateRow(4L, "Prime", false, false));

        final List<FloatingRateData> rates = service.retrieveLookupActive();

        assertThat(rates).singleElement().satisfies(rate -> {
            assertThat(rate.getId()).isEqualTo(4L);
            assertThat(rate.getName()).isEqualTo("Prime");
            assertThat(rate.getIsBaseLendingRate()).isFalse();
            assertThat(rate.getIsActive()).isTrue();
            assertThat(rate.getCreatedBy()).isNull();
            assertThat(rate.getRatePeriods()).isNull();
        });
        assertThat(jdbcTemplate.sqls).singleElement()
                .satisfies(sql -> assertThat(sql).doesNotContain("m_appuser").contains("where rate.is_active = true"));
    }

    @Test
    public void retrieveOneLoadsOnlyActiveRatePeriodsNewestFirst() {
        jdbcTemplate.rows(RATE_TABLE, rateRow(5L, "Prime", false, true));
        final Map<String, Object> period = new LinkedHashMap<>();
        period.put("id", 50L);
        period.put("fromDate", Date.valueOf(LocalDate.of(2024, 7, 1)));
        period.put("interestRate", new BigDecimal("6.5"));
        period.put("isDifferentialToBaseLendingRate", true);
        period.put("isActive", true);
        period.put("createdBy", "mifos");
        period.put("createdOnUTC", Timestamp.valueOf(UTC_CREATED));
        period.put("modifiedOn", Timestamp.valueOf(LEGACY_MODIFIED));
        final Map<String, Object> olderPeriod = new LinkedHashMap<>();
        olderPeriod.put("id", 49L);
        olderPeriod.put("createdOn", Timestamp.valueOf(LEGACY_CREATED));
        olderPeriod.put("modifiedOnUTC", Timestamp.valueOf(UTC_MODIFIED));
        jdbcTemplate.rows(PERIOD_TABLE, resultSet(period), resultSet(olderPeriod));

        final FloatingRateData rate = service.retrieveOne(5L);

        assertThat(rate.getId()).isEqualTo(5L);
        assertThat(jdbcTemplate.sqls.get(0)).contains("where rate.id = ?");
        assertThat(jdbcTemplate.args.get(0)).containsExactly(5L);
        assertThat(jdbcTemplate.sqls.get(1)).contains("where period.is_active = true and period.floating_rates_id = ?")
                .contains("order by period.from_date desc");
        assertThat(jdbcTemplate.args.get(1)).containsExactly(5L);
        assertThat(rate.getRatePeriods()).hasSize(2);
        assertThat(rate.getRatePeriods().get(1)).satisfies(p -> {
            assertThat(p.getId()).isEqualTo(49L);
            assertThat(p.getFromDate()).isNull();
            assertThat(p.getCreatedOn().toInstant()).isEqualTo(Timestamp.valueOf(LEGACY_CREATED).toInstant());
            assertThat(p.getModifiedOn().toInstant()).isEqualTo(Timestamp.valueOf(UTC_MODIFIED).toInstant());
        });
        assertThat(rate.getRatePeriods().get(0)).satisfies(p -> {
            assertThat(p.getId()).isEqualTo(50L);
            assertThat(p.getFromDate()).isEqualTo(LocalDate.of(2024, 7, 1));
            assertThat(p.getInterestRate()).isEqualByComparingTo("6.5");
            assertThat(p.getIsDifferentialToBaseLendingRate()).isTrue();
            assertThat(p.getIsActive()).isTrue();
            assertThat(p.getCreatedBy()).isEqualTo("mifos");
            assertThat(p.getCreatedOn().toInstant()).isEqualTo(Timestamp.valueOf(UTC_CREATED).toInstant());
            assertThat(p.getModifiedOn().toInstant()).isEqualTo(Timestamp.valueOf(LEGACY_MODIFIED).toInstant());
        });
    }

    @Test
    public void retrieveOneThrowsNotFoundForUnknownId() {
        assertThatThrownBy(() -> service.retrieveOne(99L))
                .isInstanceOfSatisfying(FloatingRateNotFoundException.class,
                        e -> assertThat(e.getGlobalisationMessageCode()).isEqualTo("error.msg.floatingrate.id.invalid"))
                .hasMessageContaining("99");
    }

    @Test
    public void retrieveBaseLendingRateSelectsActiveBaseLendingRateWithPeriods() {
        jdbcTemplate.rows(RATE_TABLE, rateRow(1L, "BLR", true, true));

        final FloatingRateData blr = service.retrieveBaseLendingRate();

        assertThat(blr.getIsBaseLendingRate()).isTrue();
        assertThat(blr.getRatePeriods()).isEmpty();
        assertThat(jdbcTemplate.sqls.get(0)).contains("where rate.is_base_lending_rate = true and rate.is_active = true");
        assertThat(jdbcTemplate.args.get(0)).isEmpty();
        assertThat(jdbcTemplate.sqls.get(1)).contains(PERIOD_TABLE);
    }

    @Test
    public void retrieveBaseLendingRateThrowsNotFoundWhenNoneIsActive() {
        assertThatThrownBy(() -> service.retrieveBaseLendingRate()).isInstanceOfSatisfying(FloatingRateNotFoundException.class,
                e -> assertThat(e.getGlobalisationMessageCode()).isEqualTo("error.msg.floatingrate.base.lending.rate.not.found"));
    }

    @Test
    public void retrieveInterestRatePeriodsMapsLinkedAndBaseLendingRatePeriodsForProduct() {
        final Map<String, Object> differential = new LinkedHashMap<>();
        differential.put("linkedrateperiods_from_date", Date.valueOf(LocalDate.of(2024, 7, 1)));
        differential.put("linkedrateperiods_interest_rate", new BigDecimal("2"));
        differential.put("linkedrateperiods_is_differential_to_base_lending_rate", true);
        differential.put("baserate_from_date", Date.valueOf(LocalDate.of(2024, 1, 1)));
        differential.put("baserate_interest_rate", new BigDecimal("4"));
        final Map<String, Object> noPeriods = new LinkedHashMap<>();
        jdbcTemplate.rows(PRODUCT_TABLE, resultSet(differential), resultSet(noPeriods));

        final List<InterestRatePeriodData> periods = service.retrieveInterestRatePeriods(8L);

        assertThat(jdbcTemplate.sqls.get(0)).contains("and lp.id = ?").contains("order by linkedratePeriods_from_date desc");
        assertThat(jdbcTemplate.args.get(0)).containsExactly(8L);
        assertThat(periods).hasSize(2);
        assertThat(periods.get(0).getFromDate()).isEqualTo(LocalDate.of(2024, 7, 1));
        assertThat(periods.get(0).getInterestRate()).isEqualByComparingTo("2");
        assertThat(periods.get(0).isDifferentialToBLR()).isTrue();
        assertThat(periods.get(0).getBlrFromDate()).isEqualTo(LocalDate.of(2024, 1, 1));
        assertThat(periods.get(0).getBlrInterestRate()).isEqualByComparingTo("4");
        assertThat(periods.get(1).getFromDate()).isNull();
        assertThat(periods.get(1).getBlrFromDate()).isNull();
        assertThat(periods.get(1).isDifferentialToBLR()).isFalse();
    }

    @Test
    public void retrieveInterestRatePeriodsTranslatesEmptyResultToNotFound() {
        jdbcTemplate.failure = new EmptyResultDataAccessException(1);

        assertThatThrownBy(() -> service.retrieveInterestRatePeriods(8L)).isInstanceOfSatisfying(FloatingRateNotFoundException.class,
                e -> assertThat(e.getGlobalisationMessageCode()).isEqualTo("error.msg.floatingrate.not.found.for.product"));
    }

    private static ResultSet rateRow(final Long id, final String name, final boolean isBaseLendingRate, final boolean isActive) {
        return resultSet(rateValues(id, name, isBaseLendingRate, isActive));
    }

    private static Map<String, Object> rateValues(final Long id, final String name, final boolean isBaseLendingRate,
            final boolean isActive) {
        final Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", id);
        values.put("name", name);
        values.put("isBaseLendingRate", isBaseLendingRate);
        values.put("isActive", isActive);
        return values;
    }

    private static ResultSet resultSet(final Map<String, Object> values) {
        return mock(ResultSet.class, invocation -> {
            if (invocation.getArguments().length != 1 || !(invocation.getArgument(0) instanceof String column)) {
                return Answers.RETURNS_DEFAULTS.answer(invocation);
            }
            final Object value = values.get(column);
            return switch (invocation.getMethod().getName()) {
                case "getLong" -> value == null ? 0L : ((Number) value).longValue();
                case "getBoolean" -> value != null && (Boolean) value;
                default -> value;
            };
        });
    }

    private static final class FakeJdbcTemplate extends JdbcTemplate {

        private final Map<String, List<ResultSet>> rowsBySqlFragment = new LinkedHashMap<>();
        private final List<String> sqls = new ArrayList<>();
        private final List<Object[]> args = new ArrayList<>();
        private RuntimeException failure;

        void rows(final String sqlFragment, final ResultSet... rows) {
            rowsBySqlFragment.put(sqlFragment, List.of(rows));
        }

        @Override
        public <T> List<T> query(final String sql, final RowMapper<T> rowMapper) {
            return query(sql, rowMapper, new Object[0]);
        }

        @Override
        public <T> List<T> query(final String sql, final RowMapper<T> rowMapper, final Object... queryArgs) {
            sqls.add(sql);
            args.add(queryArgs);
            if (failure != null) {
                throw failure;
            }
            final List<T> mapped = new ArrayList<>();
            final List<ResultSet> rows = rowsBySqlFragment.entrySet().stream().filter(entry -> sql.contains(entry.getKey()))
                    .map(Map.Entry::getValue).findFirst().orElse(List.of());
            for (int i = 0; i < rows.size(); i++) {
                try {
                    mapped.add(rowMapper.mapRow(rows.get(i), i));
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            }
            return mapped;
        }

        @Override
        public <T> T queryForObject(final String sql, final RowMapper<T> rowMapper) {
            return queryForObject(sql, rowMapper, new Object[0]);
        }

        @Override
        public <T> T queryForObject(final String sql, final RowMapper<T> rowMapper, final Object... queryArgs) {
            final List<T> results = query(sql, rowMapper, queryArgs);
            if (results.isEmpty()) {
                throw new EmptyResultDataAccessException(1);
            }
            return results.get(0);
        }
    }
}
