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
import static org.assertj.core.api.Assertions.entry;
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

public class FloatingRateWriteServiceImplTest {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2024, 6, 15);
    private static final Long RATE_ID = 3L;

    private Validator validator;
    private FloatingRateRepositoryWrapper repository;
    private FloatingRateWriteServiceImpl service;

    @BeforeEach
    public void setUp() {
        ThreadLocalContextUtil.setBusinessDates(new HashMap<>(Map.of(BusinessDateType.BUSINESS_DATE, BUSINESS_DATE)));
        validator = mock(Validator.class);
        repository = mock(FloatingRateRepositoryWrapper.class);
        service = new FloatingRateWriteServiceImpl(validator, repository);
    }

    @AfterEach
    public void tearDown() {
        ThreadLocalContextUtil.reset();
    }

    @Test
    public void createPersistsRateWithDefaultFlagsAndReturnsGeneratedId() {
        doAnswer(invocation -> {
            final FloatingRate saved = invocation.getArgument(0);
            saved.setId(11L);
            return null;
        }).when(repository).saveAndFlush(any(FloatingRate.class));
        final FloatingRateCreateRequest request = new FloatingRateCreateRequest("Prime", null, null,
                List.of(new FloatingRatePeriodRequest("2024-07-01", new BigDecimal("5"), null, null, null),
                        new FloatingRatePeriodRequest("01 August 2024", new BigDecimal("6"), true, "en", "dd MMMM yyyy")));

        final FloatingRateCreateResponse response = service.create(request);

        assertThat(response.getResourceId()).isEqualTo(11L);
        final FloatingRate saved = captureSavedAndFlushed();
        assertThat(saved.getName()).isEqualTo("Prime");
        assertThat(saved.isBaseLendingRate()).isFalse();
        assertThat(saved.isActive()).isTrue();
        assertThat(saved.getFloatingRatePeriods()).extracting(FloatingRatePeriod::getFromDate).containsExactly(LocalDate.of(2024, 7, 1),
                LocalDate.of(2024, 8, 1));
        assertThat(saved.getFloatingRatePeriods()).extracting(FloatingRatePeriod::getInterestRate).containsExactly(new BigDecimal("5"),
                new BigDecimal("6"));
        assertThat(saved.getFloatingRatePeriods()).extracting(FloatingRatePeriod::isDifferentialToBaseLendingRate).containsExactly(false,
                true);
        assertThat(saved.getFloatingRatePeriods()).allMatch(FloatingRatePeriod::isActive).allMatch(p -> p.getFloatingRate() == saved);
        verify(validator).validate(request);
    }

    @Test
    public void createHonoursExplicitBaseLendingAndActiveFlags() {
        service.create(new FloatingRateCreateRequest("BLR", true, false,
                List.of(new FloatingRatePeriodRequest("2024-07-01", BigDecimal.ONE, false, null, null))));

        final FloatingRate saved = captureSavedAndFlushed();
        assertThat(saved.isBaseLendingRate()).isTrue();
        assertThat(saved.isActive()).isFalse();
        assertThat(saved.getFloatingRatePeriods()).extracting(FloatingRatePeriod::isDifferentialToBaseLendingRate).containsExactly(false);
    }

    @Test
    public void createExplicitFalseBaseLendingAndTrueActiveFlags() {
        service.create(new FloatingRateCreateRequest("Rate", false, true, null));

        final FloatingRate saved = captureSavedAndFlushed();
        assertThat(saved.isBaseLendingRate()).isFalse();
        assertThat(saved.isActive()).isTrue();
        assertThat(saved.getFloatingRatePeriods()).isNull();
    }

    @Test
    public void createRejectsInvalidRequestWithoutPersisting() {
        final FloatingRateCreateRequest request = new FloatingRateCreateRequest(" ", null, null, null);
        doReturn(Set.of(mock(ConstraintViolation.class))).when(validator).validate(request);

        assertThatThrownBy(() -> service.create(request)).isInstanceOf(ConstraintViolationException.class);
        verifyNoInteractions(repository);
    }

    @Test
    public void createDuplicateNameIsReportedAsDuplicateNameError() {
        doThrow(new DataIntegrityViolationException("insert failed", new SQLException("Duplicate entry 'Prime' for key 'unq_name'")))
                .when(repository).saveAndFlush(any(FloatingRate.class));

        assertThatThrownBy(() -> service.create(createRequest("Prime"))).isInstanceOfSatisfying(PlatformDataIntegrityException.class, e -> {
            assertThat(e.getGlobalisationMessageCode()).isEqualTo("error.msg.floatingrates.duplicate.name");
            assertThat(e.getParameterName()).isEqualTo("name");
            assertThat(e.getDefaultUserMessage()).contains("`Prime`");
        });
    }

    @Test
    public void createDuplicateActiveFromDateIsReportedAsDuplicateFromDateError() {
        doThrow(new JpaSystemException(new RuntimeException("violates unique constraint unq_rate_period"))).when(repository)
                .saveAndFlush(any(FloatingRate.class));

        assertThatThrownBy(() -> service.create(createRequest("Prime"))).isInstanceOfSatisfying(PlatformDataIntegrityException.class, e -> {
            assertThat(e.getGlobalisationMessageCode()).isEqualTo("error.msg.floatingrates.duplicate.active.fromdate");
            assertThat(e.getParameterName()).isEqualTo("fromdate");
        });
    }

    @Test
    public void createUnknownDataIntegrityIssueIsReportedAsUnknownIssue() {
        doThrow(new DataIntegrityViolationException("insert failed", new SQLException("value too long"))).when(repository)
                .saveAndFlush(any(FloatingRate.class));

        assertThatThrownBy(() -> service.create(createRequest("Prime"))).isInstanceOfSatisfying(PlatformDataIntegrityException.class, e -> {
            assertThat(e.getGlobalisationMessageCode()).isEqualTo("error.msg.floatingrates.unknown.data.integrity.issue");
            assertThat(e.getDefaultUserMessage()).isEqualTo("Unknown data integrity issue with resource: value too long");
        });
    }

    @Test
    public void createPersistenceExceptionIsClassifiedByItsRootCause() {
        doThrow(new PersistenceException("flush failed", new RuntimeException("wrapped", new SQLException("unq_name")))).when(repository)
                .saveAndFlush(any(FloatingRate.class));

        assertThatThrownBy(() -> service.create(createRequest("Prime"))).isInstanceOfSatisfying(PlatformDataIntegrityException.class,
                e -> assertThat(e.getGlobalisationMessageCode()).isEqualTo("error.msg.floatingrates.duplicate.name"));
    }

    @Test
    public void createUnknownPersistenceExceptionIsReportedAsUnknownIssue() {
        doThrow(new PersistenceException("flush failed", new SQLException("connection reset"))).when(repository)
                .saveAndFlush(any(FloatingRate.class));

        assertThatThrownBy(() -> service.create(createRequest("Prime"))).isInstanceOfSatisfying(PlatformDataIntegrityException.class, e -> {
            assertThat(e.getGlobalisationMessageCode()).isEqualTo("error.msg.floatingrates.unknown.data.integrity.issue");
            assertThat(e.getDefaultUserMessage()).isEqualTo("Unknown data integrity issue with resource: connection reset");
        });
    }

    @Test
    public void createPersistenceExceptionWithoutCauseCurrentlyThrowsNullPointerException() {
        // Characterises current behaviour: ExceptionUtils.getRootCause(null) is null and handleDataIntegrityIssues
        // dereferences it.
        doThrow(new PersistenceException("flush failed")).when(repository).saveAndFlush(any(FloatingRate.class));

        assertThatThrownBy(() -> service.create(createRequest("Prime"))).isInstanceOf(NullPointerException.class);
    }

    @Test
    public void createDataIntegrityViolationWithoutMessageCurrentlyThrowsNullPointerException() {
        // Characterises current behaviour: handleDataIntegrityIssues assumes the root cause always has a message.
        doThrow(new DataIntegrityViolationException(null)).when(repository).saveAndFlush(any(FloatingRate.class));

        assertThatThrownBy(() -> service.create(createRequest("Prime"))).isInstanceOf(NullPointerException.class);
    }

    @Test
    public void updateLooksUpRateBeforeValidating() {
        final FloatingRateUpdateRequest request = updateRequest("Renamed", null, null, null);
        when(repository.findOneWithNotFoundDetection(RATE_ID)).thenThrow(new FloatingRateNotFoundException(RATE_ID));

        assertThatThrownBy(() -> service.update(request)).isInstanceOf(FloatingRateNotFoundException.class);
        verifyNoInteractions(validator);
    }

    @Test
    public void updateRejectsInvalidRequestWithoutSaving() {
        final FloatingRate existing = existingRate("Prime", false, true, new ArrayList<>());
        final FloatingRateUpdateRequest request = updateRequest(" ", null, null, null);
        doReturn(Set.of(mock(ConstraintViolation.class))).when(validator).validate(request);

        assertThatThrownBy(() -> service.update(request)).isInstanceOf(ConstraintViolationException.class);
        assertThat(existing.getName()).isEqualTo("Prime");
        verify(repository, never()).save(any());
    }

    @Test
    public void updateWithoutAnyFieldsReportsNoChangesAndDoesNotSave() {
        existingRate("Prime", false, true, new ArrayList<>());

        final FloatingRateUpdateResponse response = service.update(updateRequest(null, null, null, null));

        assertThat(response.getResourceId()).isEqualTo(RATE_ID);
        assertThat(response.getChanges()).isEmpty();
        verify(repository, never()).save(any());
    }

    @Test
    public void updateWithUnchangedValuesReportsNoChangesAndDoesNotSave() {
        existingRate("Prime", true, false, new ArrayList<>());

        final FloatingRateUpdateResponse response = service.update(updateRequest("Prime", true, false, List.of()));

        assertThat(response.getChanges()).isEmpty();
        verify(repository, never()).save(any());
    }

    @Test
    public void updateAppliesAndReportsChangedFieldsThenSaves() {
        final FloatingRate existing = existingRate("Prime", false, true, new ArrayList<>());

        final FloatingRateUpdateResponse response = service.update(updateRequest("Renamed", true, false, null));

        assertThat(response.getChanges()).containsExactly(entry("name", "Renamed"), entry("isBaseLendingRate", true),
                entry("isActive", false));
        assertThat(existing.getName()).isEqualTo("Renamed");
        assertThat(existing.isBaseLendingRate()).isTrue();
        assertThat(existing.isActive()).isFalse();
        verify(repository).save(existing);
    }

    @Test
    public void updateWithRatePeriodsDeactivatesFuturePeriodsAndAppendsNewOnes() {
        final FloatingRatePeriod past = new FloatingRatePeriod(LocalDate.of(2024, 1, 1), BigDecimal.ONE, false, true);
        final FloatingRatePeriod future = new FloatingRatePeriod(LocalDate.of(2024, 9, 1), BigDecimal.TEN, false, true);
        final FloatingRate existing = existingRate("Prime", false, true, new ArrayList<>(List.of(past, future)));
        final List<FloatingRatePeriodRequest> ratePeriods = List
                .of(new FloatingRatePeriodRequest("2024-10-01", new BigDecimal("4"), true, null, null));

        final FloatingRateUpdateResponse response = service.update(updateRequest(null, null, null, ratePeriods));

        assertThat(response.getChanges()).containsExactly(entry("ratePeriods", ratePeriods));
        assertThat(past.isActive()).isTrue();
        assertThat(future.isActive()).isFalse();
        assertThat(existing.getFloatingRatePeriods()).hasSize(3);
        final FloatingRatePeriod added = existing.getFloatingRatePeriods().get(2);
        assertThat(added.getFromDate()).isEqualTo(LocalDate.of(2024, 10, 1));
        assertThat(added.isDifferentialToBaseLendingRate()).isTrue();
        assertThat(added.isActive()).isTrue();
        assertThat(added.getFloatingRate()).isSameAs(existing);
        verify(repository).save(existing);
    }

    @Test
    public void updateDuplicateNameIsReportedAsDuplicateNameError() {
        existingRate("Prime", false, true, new ArrayList<>());
        doThrow(new DataIntegrityViolationException("update failed", new SQLException("unq_name"))).when(repository)
                .save(any(FloatingRate.class));

        assertThatThrownBy(() -> service.update(updateRequest("Other", null, null, null)))
                .isInstanceOfSatisfying(PlatformDataIntegrityException.class, e -> {
                    assertThat(e.getGlobalisationMessageCode()).isEqualTo("error.msg.floatingrates.duplicate.name");
                    assertThat(e.getDefaultUserMessage()).contains("`Other`");
                });
    }

    @Test
    public void updatePersistenceExceptionIsClassifiedByItsRootCause() {
        existingRate("Prime", false, true, new ArrayList<>());
        doThrow(new PersistenceException("flush failed", new SQLException("unq_rate_period"))).when(repository)
                .save(any(FloatingRate.class));

        assertThatThrownBy(() -> service.update(updateRequest("Other", null, null, null))).isInstanceOfSatisfying(
                PlatformDataIntegrityException.class,
                e -> assertThat(e.getGlobalisationMessageCode()).isEqualTo("error.msg.floatingrates.duplicate.active.fromdate"));
    }

    private FloatingRate captureSavedAndFlushed() {
        final ArgumentCaptor<FloatingRate> captor = ArgumentCaptor.forClass(FloatingRate.class);
        verify(repository).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    private FloatingRate existingRate(final String name, final boolean isBaseLendingRate, final boolean isActive,
            final List<FloatingRatePeriod> periods) {
        final FloatingRate rate = new FloatingRate(name, isBaseLendingRate, isActive, periods);
        rate.setId(RATE_ID);
        when(repository.findOneWithNotFoundDetection(RATE_ID)).thenReturn(rate);
        return rate;
    }

    private static FloatingRateCreateRequest createRequest(final String name) {
        return new FloatingRateCreateRequest(name, null, null,
                List.of(new FloatingRatePeriodRequest("2024-07-01", BigDecimal.ONE, null, null, null)));
    }

    private static FloatingRateUpdateRequest updateRequest(final String name, final Boolean isBaseLendingRate, final Boolean isActive,
            final List<FloatingRatePeriodRequest> ratePeriods) {
        return new FloatingRateUpdateRequest(RATE_ID, name, isBaseLendingRate, isActive, ratePeriods);
    }
}
