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

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2024, 6, 15);
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
    void create_persistsRateWithPeriodsAndReturnsGeneratedId() {
        doAnswer(invocation -> {
            final FloatingRate toSave = invocation.getArgument(0);
            ReflectionTestUtils.setField(toSave, "id", 42L);
            return null;
        }).when(repository).saveAndFlush(any());
        final FloatingRateCreateRequest request = new FloatingRateCreateRequest("Prime", true, false,
                List.of(periodRequest("2024-09-01", "5.5", true), periodRequest("2025-01-01", "6", null),
                        periodRequest("2025-06-01", "6.5", false)));

        final FloatingRateCreateResponse response = service.create(request);

        assertThat(response.getResourceId()).isEqualTo(42L);
        final FloatingRate saved = savedOnCreate();
        assertThat(saved.getName()).isEqualTo("Prime");
        assertThat(saved.isBaseLendingRate()).isTrue();
        assertThat(saved.isActive()).isFalse();
        assertThat(saved.getFloatingRatePeriods()).hasSize(3);
        final FloatingRatePeriod first = saved.getFloatingRatePeriods().get(0);
        final FloatingRatePeriod second = saved.getFloatingRatePeriods().get(1);
        assertThat(first.getFromDate()).isEqualTo(LocalDate.of(2024, 9, 1));
        assertThat(first.getInterestRate()).isEqualByComparingTo("5.5");
        assertThat(first.isDifferentialToBaseLendingRate()).isTrue();
        assertThat(second.getFromDate()).isEqualTo(LocalDate.of(2025, 1, 1));
        assertThat(second.isDifferentialToBaseLendingRate()).isFalse();
        assertThat(saved.getFloatingRatePeriods().get(2).isDifferentialToBaseLendingRate()).isFalse();
        assertThat(saved.getFloatingRatePeriods()).allSatisfy(period -> {
            assertThat(period.isActive()).isTrue();
            assertThat(period.getFloatingRate()).isSameAs(saved);
        });
    }

    @Test
    void create_withoutFlags_defaultsToActiveNonBaseLendingRate() {
        service.create(new FloatingRateCreateRequest("Prime", null, null, List.of(periodRequest("2024-09-01", "5", null))));

        final FloatingRate saved = savedOnCreate();
        assertThat(saved.isBaseLendingRate()).isFalse();
        assertThat(saved.isActive()).isTrue();
    }

    @Test
    void create_parsesFromDateUsingRequestedFormatAndLocale() {
        final FloatingRatePeriodRequest period = new FloatingRatePeriodRequest("01 september 2024", new BigDecimal("5"), null, "en",
                "dd MMMM yyyy");

        service.create(new FloatingRateCreateRequest("Prime", false, true, List.of(period)));

        assertThat(savedOnCreate().getFloatingRatePeriods().get(0).getFromDate()).isEqualTo(LocalDate.of(2024, 9, 1));
    }

    @Test
    void create_withoutRatePeriods_persistsRateWithoutPeriodList() {
        service.create(new FloatingRateCreateRequest("Prime", false, true, null));

        assertThat(savedOnCreate().getFloatingRatePeriods()).isNull();
    }

    @Test
    void create_invalidRequest_isRejectedWithoutSaving() {
        final FloatingRateCreateRequest request = new FloatingRateCreateRequest(" ", false, true, List.of());
        when(validator.validate(request)).thenReturn(Set.of(violation()));

        assertThatThrownBy(() -> service.create(request)).isInstanceOf(ConstraintViolationException.class);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void create_duplicateName_isReportedAsDuplicateName() {
        doThrow(new DataIntegrityViolationException("insert failed", new SQLException("Duplicate entry 'Prime' for key 'unq_name'")))
                .when(repository).saveAndFlush(any());

        assertDataIntegrityError(() -> service.create(validCreateRequest()), "error.msg.floatingrates.duplicate.name", "name");
    }

    @Test
    void create_duplicateActiveFromDate_isReportedAsDuplicateFromDate() {
        doThrow(new DataIntegrityViolationException("insert failed", new SQLException("violates unq_rate_period"))).when(repository)
                .saveAndFlush(any());

        assertDataIntegrityError(() -> service.create(validCreateRequest()), "error.msg.floatingrates.duplicate.active.fromdate",
                "fromdate");
    }

    @Test
    void create_unrecognisedIntegrityViolation_isReportedAsUnknownIntegrityIssue() {
        doThrow(new DataIntegrityViolationException("insert failed", new SQLException("value too long for column"))).when(repository)
                .saveAndFlush(any());

        assertThatThrownBy(() -> service.create(validCreateRequest())).isInstanceOf(PlatformDataIntegrityException.class)
                .extracting("globalisationMessageCode").isEqualTo("error.msg.floatingrates.unknown.data.integrity.issue");
    }

    @Test
    void create_jpaSystemException_isTranslatedLikeIntegrityViolation() {
        doThrow(new JpaSystemException(new RuntimeException("unq_name"))).when(repository).saveAndFlush(any());

        assertDataIntegrityError(() -> service.create(validCreateRequest()), "error.msg.floatingrates.duplicate.name", "name");
    }

    @Test
    void create_persistenceException_isTranslatedUsingItsRootCause() {
        doThrow(new PersistenceException("flush failed", new RuntimeException("wrapper", new SQLException("unq_name")))).when(repository)
                .saveAndFlush(any());

        assertDataIntegrityError(() -> service.create(validCreateRequest()), "error.msg.floatingrates.duplicate.name", "name");
    }

    @Test
    void create_persistenceExceptionWithUnrecognisedCause_isReportedAsUnknownIntegrityIssue() {
        doThrow(new PersistenceException("flush failed", new SQLException("deadlock detected"))).when(repository).saveAndFlush(any());

        assertThatThrownBy(() -> service.create(validCreateRequest())).isInstanceOf(PlatformDataIntegrityException.class)
                .extracting("globalisationMessageCode").isEqualTo("error.msg.floatingrates.unknown.data.integrity.issue");
    }

    // Likely bug (documented in PR): the original persistence error is masked by an NPE.
    @Test
    void create_persistenceExceptionWithoutCause_throwsNullPointerException() {
        doThrow(new PersistenceException("flush failed")).when(repository).saveAndFlush(any());

        assertThatThrownBy(() -> service.create(validCreateRequest())).isInstanceOf(NullPointerException.class);
    }

    // Likely bug (documented in PR): the original database error is masked by an NPE.
    @Test
    void create_integrityViolationWithoutMessage_throwsNullPointerException() {
        doThrow(new DataIntegrityViolationException("insert failed", new SQLException())).when(repository).saveAndFlush(any());

        assertThatThrownBy(() -> service.create(validCreateRequest())).isInstanceOf(NullPointerException.class);
    }

    @Test
    void update_unknownRate_isRejectedBeforeValidation() {
        when(repository.findOneWithNotFoundDetection(99L)).thenThrow(new FloatingRateNotFoundException(99L));

        assertThatThrownBy(() -> service.update(new FloatingRateUpdateRequest(99L, "Prime", null, null, null)))
                .isInstanceOf(FloatingRateNotFoundException.class);
        verifyNoInteractions(validator);
        verify(repository, never()).save(any());
    }

    @Test
    void update_invalidRequest_isRejectedWithoutSaving() {
        existingRate();
        final FloatingRateUpdateRequest request = new FloatingRateUpdateRequest(RATE_ID, " ", null, null, null);
        when(validator.validate(request)).thenReturn(Set.of(violation()));

        assertThatThrownBy(() -> service.update(request)).isInstanceOf(ConstraintViolationException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void update_withOnlyCurrentValues_reportsNoChangesAndDoesNotSave() {
        final FloatingRate rate = existingRate();

        final FloatingRateUpdateResponse response = service.update(new FloatingRateUpdateRequest(RATE_ID, "Prime", false, true, null));

        assertThat(response.getResourceId()).isEqualTo(RATE_ID);
        assertThat(response.getChanges()).isEmpty();
        assertThat(rate.getFloatingRatePeriods()).allSatisfy(period -> assertThat(period.isActive()).isTrue());
        verify(repository, never()).save(any());
    }

    @Test
    void update_withNoFields_reportsNoChangesAndDoesNotSave() {
        existingRate();

        final FloatingRateUpdateResponse response = service.update(new FloatingRateUpdateRequest(RATE_ID, null, null, null, null));

        assertThat(response.getChanges()).isEmpty();
        verify(repository, never()).save(any());
    }

    @Test
    void update_withEmptyRatePeriodList_doesNotTouchPeriods() {
        final FloatingRate rate = existingRate();

        final FloatingRateUpdateResponse response = service
                .update(new FloatingRateUpdateRequest(RATE_ID, null, null, null, new ArrayList<>()));

        assertThat(response.getChanges()).isEmpty();
        assertThat(rate.getFloatingRatePeriods()).hasSize(2);
        verify(repository, never()).save(any());
    }

    @Test
    void update_rename_appliesAndReportsOnlyTheName() {
        final FloatingRate rate = existingRate();

        final FloatingRateUpdateResponse response = service.update(new FloatingRateUpdateRequest(RATE_ID, "Prime Plus", null, null, null));

        assertThat(response.getChanges()).containsExactly(Map.entry("name", "Prime Plus"));
        assertThat(rate.getName()).isEqualTo("Prime Plus");
        verify(repository).save(rate);
    }

    @Test
    void update_flagChanges_areAppliedAndReported() {
        final FloatingRate rate = existingRate();

        final FloatingRateUpdateResponse response = service.update(new FloatingRateUpdateRequest(RATE_ID, null, true, false, null));

        assertThat(response.getChanges()).containsExactly(Map.entry("isBaseLendingRate", true), Map.entry("isActive", false));
        assertThat(rate.isBaseLendingRate()).isTrue();
        assertThat(rate.isActive()).isFalse();
        verify(repository).save(rate);
    }

    @Test
    void update_newRatePeriods_deactivateFuturePeriodsKeepPastOnesAndAppendNewOnes() {
        final FloatingRate rate = existingRate();
        final FloatingRatePeriod past = rate.getFloatingRatePeriods().get(0);
        final FloatingRatePeriod future = rate.getFloatingRatePeriods().get(1);
        final List<FloatingRatePeriodRequest> periods = List.of(periodRequest("2024-10-01", "6.5", null));

        final FloatingRateUpdateResponse response = service.update(new FloatingRateUpdateRequest(RATE_ID, null, null, null, periods));

        assertThat(response.getChanges()).containsExactly(Map.entry("ratePeriods", periods));
        assertThat(past.isActive()).isTrue();
        assertThat(future.isActive()).isFalse();
        assertThat(rate.getFloatingRatePeriods()).hasSize(3);
        final FloatingRatePeriod added = rate.getFloatingRatePeriods().get(2);
        assertThat(added.getFromDate()).isEqualTo(LocalDate.of(2024, 10, 1));
        assertThat(added.getInterestRate()).isEqualByComparingTo("6.5");
        assertThat(added.isActive()).isTrue();
        assertThat(added.getFloatingRate()).isSameAs(rate);
        verify(repository).save(rate);
    }

    @Test
    void update_allChanges_areReportedInRequestFieldOrder() {
        existingRate();
        final List<FloatingRatePeriodRequest> periods = List.of(periodRequest("2024-10-01", "6.5", null));

        final FloatingRateUpdateResponse response = service.update(new FloatingRateUpdateRequest(RATE_ID, "Renamed", true, false, periods));

        assertThat(response.getChanges()).containsOnlyKeys("name", "isBaseLendingRate", "isActive", "ratePeriods");
        assertThat(new ArrayList<>(response.getChanges().keySet())).containsExactly("name", "isBaseLendingRate", "isActive", "ratePeriods");
    }

    @Test
    void update_duplicateNameOnSave_isReportedAsDuplicateName() {
        existingRate();
        doThrow(new DataIntegrityViolationException("update failed", new SQLException("unq_name"))).when(repository).save(any());

        assertDataIntegrityError(() -> service.update(new FloatingRateUpdateRequest(RATE_ID, "Taken", null, null, null)),
                "error.msg.floatingrates.duplicate.name", "name");
    }

    @Test
    void update_persistenceExceptionOnSave_isTranslatedUsingItsRootCause() {
        existingRate();
        doThrow(new PersistenceException("flush failed", new SQLException("unq_rate_period"))).when(repository).save(any());
        final List<FloatingRatePeriodRequest> periods = List.of(periodRequest("2024-10-01", "6.5", null));

        assertDataIntegrityError(() -> service.update(new FloatingRateUpdateRequest(RATE_ID, null, null, null, periods)),
                "error.msg.floatingrates.duplicate.active.fromdate", "fromdate");
    }

    private FloatingRate existingRate() {
        final FloatingRate rate = new FloatingRate("Prime", false, true,
                new ArrayList<>(List.of(new FloatingRatePeriod(LocalDate.of(2024, 1, 1), new BigDecimal("5"), false, true),
                        new FloatingRatePeriod(LocalDate.of(2024, 9, 1), new BigDecimal("6"), false, true))));
        when(repository.findOneWithNotFoundDetection(RATE_ID)).thenReturn(rate);
        return rate;
    }

    private FloatingRate savedOnCreate() {
        final ArgumentCaptor<FloatingRate> captor = ArgumentCaptor.forClass(FloatingRate.class);
        verify(repository).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    private static FloatingRateCreateRequest validCreateRequest() {
        return new FloatingRateCreateRequest("Prime", false, true, List.of(periodRequest("2024-09-01", "5", null)));
    }

    private static FloatingRatePeriodRequest periodRequest(final String fromDate, final String rate, final Boolean differential) {
        return new FloatingRatePeriodRequest(fromDate, new BigDecimal(rate), differential, null, null);
    }

    @SuppressWarnings("unchecked")
    private static <T> ConstraintViolation<T> violation() {
        return mock(ConstraintViolation.class);
    }

    private static void assertDataIntegrityError(final org.assertj.core.api.ThrowableAssert.ThrowingCallable call, final String code,
            final String parameter) {
        assertThatThrownBy(call).isInstanceOfSatisfying(PlatformDataIntegrityException.class, e -> {
            assertThat(e.getGlobalisationMessageCode()).isEqualTo(code);
            assertThat(e.getParameterName()).isEqualTo(parameter);
        });
    }
}
