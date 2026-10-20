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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.persistence.PersistenceException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.exception.PlatformDataIntegrityException;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.portfolio.floatingrates.data.FloatingRateCreateRequest;
import org.apache.fineract.portfolio.floatingrates.data.FloatingRateCreateResponse;
import org.apache.fineract.portfolio.floatingrates.data.FloatingRatePeriodRequest;
import org.apache.fineract.portfolio.floatingrates.data.FloatingRateUpdateRequest;
import org.apache.fineract.portfolio.floatingrates.data.FloatingRateUpdateResponse;
import org.apache.fineract.portfolio.floatingrates.domain.FloatingRate;
import org.apache.fineract.portfolio.floatingrates.domain.FloatingRatePeriod;
import org.apache.fineract.portfolio.floatingrates.domain.FloatingRateRepositoryWrapper;
import org.apache.fineract.portfolio.floatingrates.exception.FloatingRateNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.test.util.ReflectionTestUtils;

class FloatingRateWriteServiceImplTest {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 3, 15);
    private static final Long RATE_ID = 7L;

    private Validator validator;
    private FloatingRateRepositoryWrapper repository;
    private FloatingRateWriteServiceImpl service;

    @BeforeEach
    void setUp() {
        ThreadLocalContextUtil.setBusinessDates(new HashMap<>(Map.of(BusinessDateType.BUSINESS_DATE, BUSINESS_DATE)));
        validator = mock(Validator.class);
        repository = mock(FloatingRateRepositoryWrapper.class);
        service = new FloatingRateWriteServiceImpl(validator, repository);
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.reset();
    }

    @Test
    void createDefaultsToActiveNonBaseLendingRateAndReturnsGeneratedId() {
        doAnswer(invocation -> {
            final FloatingRate saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", RATE_ID);
            return null;
        }).when(repository).saveAndFlush(any(FloatingRate.class));
        final FloatingRateCreateRequest request = new FloatingRateCreateRequest("Prime", null, null,
                List.of(period("2026-04-01", "5.5", null)));

        final FloatingRateCreateResponse response = service.create(request);

        assertThat(response.getResourceId()).isEqualTo(RATE_ID);
        final FloatingRate saved = captureSavedAndFlushed();
        assertThat(saved.getName()).isEqualTo("Prime");
        assertThat(saved.isActive()).isTrue();
        assertThat(saved.isBaseLendingRate()).isFalse();
    }

    @Test
    void createHonoursExplicitFlags() {
        final FloatingRateCreateRequest request = new FloatingRateCreateRequest("BLR", true, false,
                List.of(period("2026-04-01", "4", null)));

        service.create(request);

        final FloatingRate saved = captureSavedAndFlushed();
        assertThat(saved.isBaseLendingRate()).isTrue();
        assertThat(saved.isActive()).isFalse();
    }

    @Test
    void createBuildsActivePeriodsLinkedToTheRate() {
        final FloatingRatePeriodRequest formatted = new FloatingRatePeriodRequest("01 May 2026", new BigDecimal("6.25"), true, "en",
                "dd MMMM yyyy");
        final FloatingRateCreateRequest request = new FloatingRateCreateRequest("Prime", false, true,
                List.of(period("2026-04-01", "5.5", null), formatted, period("2026-06-01", "7", false)));

        service.create(request);

        final FloatingRate saved = captureSavedAndFlushed();
        assertThat(saved.getFloatingRatePeriods()).hasSize(3);
        final FloatingRatePeriod first = saved.getFloatingRatePeriods().get(0);
        assertThat(first.getFromDate()).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(first.getInterestRate()).isEqualByComparingTo("5.5");
        assertThat(first.isDifferentialToBaseLendingRate()).isFalse();
        assertThat(first.isActive()).isTrue();
        assertThat(first.getFloatingRate()).isSameAs(saved);
        final FloatingRatePeriod second = saved.getFloatingRatePeriods().get(1);
        assertThat(second.getFromDate()).isEqualTo(LocalDate.of(2026, 5, 1));
        assertThat(second.isDifferentialToBaseLendingRate()).isTrue();
        assertThat(second.getFloatingRate()).isSameAs(saved);
        assertThat(saved.getFloatingRatePeriods().get(2).isDifferentialToBaseLendingRate()).isFalse();
    }

    @Test
    void createWithoutRatePeriodsSavesRateWithNoPeriods() {
        service.create(new FloatingRateCreateRequest("Prime", null, null, null));

        assertThat(captureSavedAndFlushed().getFloatingRatePeriods()).isNull();
    }

    @Test
    void createRejectsInvalidRequestWithoutSaving() {
        final FloatingRateCreateRequest request = new FloatingRateCreateRequest(" ", null, null, null);
        doReturn(Set.of(mock(ConstraintViolation.class))).when(validator).validate(request);

        assertThatThrownBy(() -> service.create(request)).isInstanceOf(ConstraintViolationException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void createMapsDuplicateNameToDomainError() {
        doThrow(new DataIntegrityViolationException("dup", new SQLException("Duplicate entry 'Prime' for key 'unq_name'"))).when(repository)
                .saveAndFlush(any(FloatingRate.class));

        assertThatThrownBy(() -> service.create(validCreateRequest())).isInstanceOfSatisfying(PlatformDataIntegrityException.class, e -> {
            assertThat(e.getGlobalisationMessageCode()).isEqualTo("error.msg.floatingrates.duplicate.name");
            assertThat(e.getParameterName()).isEqualTo("name");
            assertThat(e.getDefaultUserMessage()).contains("Prime");
        });
    }

    @Test
    void createMapsDuplicateRatePeriodFromDateToDomainError() {
        doThrow(new JpaSystemException(new RuntimeException("violates unique constraint unq_rate_period"))).when(repository)
                .saveAndFlush(any(FloatingRate.class));

        assertThatThrownBy(() -> service.create(validCreateRequest())).isInstanceOfSatisfying(PlatformDataIntegrityException.class,
                e -> assertThat(e.getGlobalisationMessageCode()).isEqualTo("error.msg.floatingrates.duplicate.active.fromdate"));
    }

    @Test
    void createMapsUnknownIntegrityViolationToGenericDomainError() {
        doThrow(new DataIntegrityViolationException("boom", new SQLException("check constraint chk_rate"))).when(repository)
                .saveAndFlush(any(FloatingRate.class));

        assertThatThrownBy(() -> service.create(validCreateRequest())).isInstanceOfSatisfying(PlatformDataIntegrityException.class,
                e -> assertThat(e.getGlobalisationMessageCode()).isEqualTo("error.msg.floatingrates.unknown.data.integrity.issue"));
    }

    @Test
    void createUsesRootCauseOfPersistenceException() {
        final PersistenceException wrapped = new PersistenceException("flush failed",
                new RuntimeException("wrapper", new SQLException("Duplicate entry for key 'unq_name'")));
        doThrow(wrapped).when(repository).saveAndFlush(any(FloatingRate.class));

        assertThatThrownBy(() -> service.create(validCreateRequest())).isInstanceOfSatisfying(PlatformDataIntegrityException.class,
                e -> assertThat(e.getGlobalisationMessageCode()).isEqualTo("error.msg.floatingrates.duplicate.name"));
    }

    @Test
    void createMapsUnknownPersistenceExceptionToGenericDomainError() {
        final PersistenceException wrapped = new PersistenceException("flush failed",
                new RuntimeException("wrapper", new SQLException("disk full")));
        doThrow(wrapped).when(repository).saveAndFlush(any(FloatingRate.class));

        assertThatThrownBy(() -> service.create(validCreateRequest())).isInstanceOfSatisfying(PlatformDataIntegrityException.class,
                e -> assertThat(e.getGlobalisationMessageCode()).isEqualTo("error.msg.floatingrates.unknown.data.integrity.issue"));
    }

    @Test
    void createPersistenceExceptionWithoutCauseCurrentlyFailsWithNullPointer() {
        doThrow(new PersistenceException("flush failed")).when(repository).saveAndFlush(any(FloatingRate.class));

        assertThatThrownBy(() -> service.create(validCreateRequest())).isInstanceOf(NullPointerException.class);
    }

    @Test
    void updateUnknownRateFailsBeforeValidation() {
        when(repository.findOneWithNotFoundDetection(99L)).thenThrow(new FloatingRateNotFoundException(99L));

        assertThatThrownBy(() -> service.update(new FloatingRateUpdateRequest(99L, "X", null, null, null)))
                .isInstanceOf(FloatingRateNotFoundException.class);
        verifyNoInteractions(validator);
        verify(repository, never()).save(any());
    }

    @Test
    void updateRejectsInvalidRequestWithoutSaving() {
        final FloatingRate existing = existingRate("Prime", false, true, new ArrayList<>());
        when(repository.findOneWithNotFoundDetection(RATE_ID)).thenReturn(existing);
        final FloatingRateUpdateRequest request = new FloatingRateUpdateRequest(RATE_ID, " ", null, null, null);
        doReturn(Set.of(mock(ConstraintViolation.class))).when(validator).validate(request);

        assertThatThrownBy(() -> service.update(request)).isInstanceOf(ConstraintViolationException.class);
        verify(repository, never()).save(any());
        assertThat(existing.getName()).isEqualTo("Prime");
    }

    @Test
    void updateWithNoEffectiveChangesDoesNotSave() {
        final FloatingRate existing = existingRate("Prime", false, true, new ArrayList<>());
        when(repository.findOneWithNotFoundDetection(RATE_ID)).thenReturn(existing);

        final FloatingRateUpdateResponse response = service.update(new FloatingRateUpdateRequest(RATE_ID, "Prime", false, true, null));

        assertThat(response.getResourceId()).isEqualTo(RATE_ID);
        assertThat(response.getChanges()).isEmpty();
        verify(repository, never()).save(any());
    }

    @Test
    void updateWithAllFieldsOmittedDoesNotSave() {
        when(repository.findOneWithNotFoundDetection(RATE_ID)).thenReturn(existingRate("Prime", false, true, new ArrayList<>()));

        final FloatingRateUpdateResponse response = service.update(new FloatingRateUpdateRequest(RATE_ID, null, null, null, null));

        assertThat(response.getChanges()).isEmpty();
        verify(repository, never()).save(any());
    }

    @Test
    void updateRecordsAndPersistsOnlyChangedFields() {
        final FloatingRate existing = existingRate("Prime", false, true, new ArrayList<>());
        when(repository.findOneWithNotFoundDetection(RATE_ID)).thenReturn(existing);

        final FloatingRateUpdateResponse response = service.update(new FloatingRateUpdateRequest(RATE_ID, "Prime Plus", true, false, null));

        assertThat(response.getChanges()).containsExactly(Map.entry("name", "Prime Plus"), Map.entry("isBaseLendingRate", true),
                Map.entry("isActive", false));
        assertThat(existing.getName()).isEqualTo("Prime Plus");
        assertThat(existing.isBaseLendingRate()).isTrue();
        assertThat(existing.isActive()).isFalse();
        verify(repository).save(existing);
    }

    @Test
    void updateRatePeriodsDeactivatesFuturePeriodsKeepsHistoryAndAppendsNewOnes() {
        final FloatingRatePeriod past = new FloatingRatePeriod(BUSINESS_DATE.minusMonths(2), new BigDecimal("5"), false, true);
        final FloatingRatePeriod today = new FloatingRatePeriod(BUSINESS_DATE, new BigDecimal("5.5"), false, true);
        final FloatingRatePeriod future = new FloatingRatePeriod(BUSINESS_DATE.plusMonths(1), new BigDecimal("6"), false, true);
        final FloatingRate existing = existingRate("Prime", false, true, new ArrayList<>(List.of(past, today, future)));
        when(repository.findOneWithNotFoundDetection(RATE_ID)).thenReturn(existing);
        final List<FloatingRatePeriodRequest> newPeriods = List.of(period(BUSINESS_DATE.plusMonths(2).toString(), "7", true));

        final FloatingRateUpdateResponse response = service.update(new FloatingRateUpdateRequest(RATE_ID, null, null, null, newPeriods));

        assertThat(response.getChanges()).containsExactly(Map.entry("ratePeriods", newPeriods));
        assertThat(past.isActive()).isTrue();
        assertThat(today.isActive()).isTrue();
        assertThat(future.isActive()).isFalse();
        assertThat(existing.getFloatingRatePeriods()).hasSize(4);
        final FloatingRatePeriod added = existing.getFloatingRatePeriods().get(3);
        assertThat(added.getFromDate()).isEqualTo(BUSINESS_DATE.plusMonths(2));
        assertThat(added.isActive()).isTrue();
        assertThat(added.isDifferentialToBaseLendingRate()).isTrue();
        assertThat(added.getFloatingRate()).isSameAs(existing);
        verify(repository).save(existing);
    }

    @Test
    void updateWithEmptyRatePeriodListLeavesPeriodsUntouched() {
        final FloatingRatePeriod future = new FloatingRatePeriod(BUSINESS_DATE.plusMonths(1), new BigDecimal("6"), false, true);
        final FloatingRate existing = existingRate("Prime", false, true, new ArrayList<>(List.of(future)));
        when(repository.findOneWithNotFoundDetection(RATE_ID)).thenReturn(existing);

        final FloatingRateUpdateResponse response = service.update(new FloatingRateUpdateRequest(RATE_ID, null, null, null, List.of()));

        assertThat(response.getChanges()).isEmpty();
        assertThat(future.isActive()).isTrue();
        verify(repository, never()).save(any());
    }

    @Test
    void updateMapsDuplicateNameToDomainError() {
        when(repository.findOneWithNotFoundDetection(RATE_ID)).thenReturn(existingRate("Prime", false, true, new ArrayList<>()));
        doThrow(new DataIntegrityViolationException("dup", new SQLException("unq_name"))).when(repository).save(any(FloatingRate.class));

        assertThatThrownBy(() -> service.update(new FloatingRateUpdateRequest(RATE_ID, "Other", null, null, null)))
                .isInstanceOfSatisfying(PlatformDataIntegrityException.class,
                        e -> assertThat(e.getGlobalisationMessageCode()).isEqualTo("error.msg.floatingrates.duplicate.name"));
    }

    @Test
    void updateMapsPersistenceExceptionRootCauseToDomainError() {
        when(repository.findOneWithNotFoundDetection(RATE_ID)).thenReturn(existingRate("Prime", false, true, new ArrayList<>()));
        doThrow(new PersistenceException("flush", new RuntimeException("w", new SQLException("unq_rate_period")))).when(repository)
                .save(any(FloatingRate.class));

        assertThatThrownBy(() -> service.update(new FloatingRateUpdateRequest(RATE_ID, "Other", null, null, null)))
                .isInstanceOfSatisfying(PlatformDataIntegrityException.class,
                        e -> assertThat(e.getGlobalisationMessageCode()).isEqualTo("error.msg.floatingrates.duplicate.active.fromdate"));
    }

    private FloatingRate captureSavedAndFlushed() {
        final ArgumentCaptor<FloatingRate> captor = ArgumentCaptor.forClass(FloatingRate.class);
        verify(repository).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    private static FloatingRateCreateRequest validCreateRequest() {
        return new FloatingRateCreateRequest("Prime", null, null, List.of(period("2026-04-01", "5", null)));
    }

    private static FloatingRatePeriodRequest period(final String fromDate, final String rate, final Boolean differential) {
        return new FloatingRatePeriodRequest(fromDate, new BigDecimal(rate), differential, null, null);
    }

    private static FloatingRate existingRate(final String name, final boolean blr, final boolean active,
            final List<FloatingRatePeriod> periods) {
        final FloatingRate rate = new FloatingRate(name, blr, active, periods);
        ReflectionTestUtils.setField(rate, "id", RATE_ID);
        return rate;
    }
}
