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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.apache.fineract.portfolio.floatingrates.exception.FloatingRateNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FloatingRateRepositoryWrapperTest {

    private FloatingRateRepository repository;
    private FloatingRateRepositoryWrapper wrapper;

    @BeforeEach
    void setUp() {
        repository = mock(FloatingRateRepository.class);
        wrapper = new FloatingRateRepositoryWrapper(repository);
    }

    @Test
    void findOneReturnsExistingRate() {
        final FloatingRate rate = new FloatingRate("Prime", false, true, null);
        when(repository.findById(1L)).thenReturn(Optional.of(rate));

        assertThat(wrapper.findOneWithNotFoundDetection(1L)).isSameAs(rate);
    }

    @Test
    void findOneThrowsNotFoundForUnknownId() {
        when(repository.findById(2L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> wrapper.findOneWithNotFoundDetection(2L)).isInstanceOfSatisfying(FloatingRateNotFoundException.class,
                e -> assertThat(e.getGlobalisationMessageCode()).isEqualTo("error.msg.floatingrate.id.invalid"));
    }

    @Test
    void delegatesBaseLendingRateLookupAndPersistence() {
        final FloatingRate blr = new FloatingRate("BLR", true, true, null);
        when(repository.retrieveBaseLendingRate()).thenReturn(blr);

        assertThat(wrapper.retrieveBaseLendingRate()).isSameAs(blr);

        wrapper.save(blr);
        wrapper.saveAndFlush(blr);
        verify(repository).save(blr);
        verify(repository).saveAndFlush(blr);
    }
}
