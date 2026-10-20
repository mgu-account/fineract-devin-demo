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
package org.apache.fineract.integrationtests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.apache.fineract.client.models.FloatingRateCreateRequest;
import org.apache.fineract.client.models.FloatingRateCreateResponse;
import org.apache.fineract.client.models.FloatingRatePeriodRequest;
import org.apache.fineract.client.models.FloatingRateUpdateRequest;
import org.apache.fineract.client.models.GetLoansLoanIdRepaymentPeriod;
import org.apache.fineract.integrationtests.client.feign.FeignLoanTestBase;
import org.apache.fineract.integrationtests.client.feign.helpers.FeignRawHttpHelper;
import org.apache.fineract.integrationtests.client.feign.modules.LoanRequestBuilders;
import org.apache.fineract.integrationtests.common.Utils;
import org.apache.fineract.integrationtests.common.accounting.Account;
import org.apache.fineract.integrationtests.common.loans.LoanProductTestBuilder;
import org.junit.jupiter.api.Test;

/**
 * A floating rate change made after disbursement must reach the existing loan through the "Recalculate Interest For
 * Loans" job, without any loan transaction triggering a schedule regeneration.
 */
public class FloatingRateChangeRecalculationJobTest extends FeignLoanTestBase {

    private static final String RECALCULATE_INTEREST_JOB = "Recalculate Interest For Loans";
    private static final BigDecimal INITIAL_INTEREST_RATE = new BigDecimal("12");
    private static final BigDecimal CHANGED_INTEREST_RATE = new BigDecimal("6");

    @Test
    public void recalculateInterestJobRepricesLoanAfterFloatingRateChange() {
        runAt("01 February 2024", () -> {
            final Long floatingRateId = createFloatingRate();
            final Integer loanProductId = createFloatingRateLoanProduct(floatingRateId, accountHelper.createAssetAccount("frJobAsset"),
                    accountHelper.createIncomeAccount("frJobIncome"), accountHelper.createExpenseAccount("frJobExpense"),
                    accountHelper.createLiabilityAccount("frJobOverpayment"));
            final Long clientId = createClient();

            updateBusinessDate("15 March 2024");
            final Long loanId = createAndDisburseFloatingRateLoan(clientId, loanProductId, "15 March 2024");
            final Map<Integer, BigDecimal> initialDue = totalDueByPeriod(loanId);

            updateBusinessDate("20 March 2024");
            ok(() -> fineractClient().floatingRates().updateFloatingRate(floatingRateId,
                    new FloatingRateUpdateRequest().ratePeriods(List.of(ratePeriod("01 April 2024", CHANGED_INTEREST_RATE)))));
            assertEquals(initialDue, totalDueByPeriod(loanId), "A rate change alone must not regenerate the schedule");

            updateBusinessDate("21 March 2024");
            schedulerHelper.executeAndAwaitJob(RECALCULATE_INTEREST_JOB);

            final Map<Integer, BigDecimal> repricedDue = totalDueByPeriod(loanId);
            for (Map.Entry<Integer, BigDecimal> entry : repricedDue.entrySet()) {
                if (entry.getKey() > 1) {
                    assertTrue(entry.getValue().compareTo(initialDue.get(entry.getKey())) < 0, "Period " + entry.getKey() + " due "
                            + entry.getValue() + " should drop below " + initialDue.get(entry.getKey()) + " after the job reprices the loan");
                }
            }
        });
    }

    private Map<Integer, BigDecimal> totalDueByPeriod(Long loanId) {
        final Map<Integer, BigDecimal> dueByPeriod = new TreeMap<>();
        for (GetLoansLoanIdRepaymentPeriod period : getLoanDetails(loanId).getRepaymentSchedule().getPeriods()) {
            if (period.getPeriod() != null) {
                dueByPeriod.put(period.getPeriod(), period.getTotalDueForPeriod());
            }
        }
        assertTrue(dueByPeriod.size() > 1, "Expected a multi-period schedule");
        return dueByPeriod;
    }

    private static FloatingRatePeriodRequest ratePeriod(String fromDate, BigDecimal rate) {
        return new FloatingRatePeriodRequest().fromDate(fromDate).interestRate(rate).isDifferentialToBaseLendingRate(false).locale("en")
                .dateFormat("dd MMMM yyyy");
    }

    private Long createFloatingRate() {
        final FloatingRateCreateResponse response = ok(() -> fineractClient().floatingRates()
                .createFloatingRate(new FloatingRateCreateRequest().name(Utils.uniqueRandomStringGenerator("FR_JOB_", 6))
                        .isBaseLendingRate(false).isActive(true).ratePeriods(List.of(ratePeriod("01 March 2024", INITIAL_INTEREST_RATE)))));
        assertNotNull(response.getResourceId());
        return response.getResourceId();
    }

    private Integer createFloatingRateLoanProduct(Long floatingRateId, Account... accounts) {
        final HashMap<String, Object> loanProductMap = new LoanProductTestBuilder().withPrincipal("10000").withNumberOfRepayments("12")
                .withRepaymentTypeAsMonth().withRepaymentAfterEvery("1").withInterestTypeAsDecliningBalance()
                .withAmortizationTypeAsEqualInstallments().withInterestCalculationPeriodTypeAsRepaymentPeriod(true)
                .withInterestRecalculationDetails(LoanProductTestBuilder.RECALCULATION_COMPOUNDING_METHOD_NONE,
                        LoanProductTestBuilder.RECALCULATION_STRATEGY_REDUCE_EMI_AMOUN,
                        LoanProductTestBuilder.INTEREST_APPLICABLE_STRATEGY_ON_PRE_CLOSE_DATE)
                .withInterestRecalculationRestFrequencyDetails(LoanProductTestBuilder.RECALCULATION_FREQUENCY_TYPE_DAILY, "1", null, null)
                .withDaysInMonth("30").withDaysInYear("360").withAccountingRulePeriodicAccrual(accounts).build(null, null);
        loanProductMap.remove("interestRatePerPeriod");
        loanProductMap.remove("interestRateFrequencyType");
        loanProductMap.put("isLinkedToFloatingInterestRates", true);
        loanProductMap.put("floatingRatesId", floatingRateId);
        loanProductMap.put("interestRateDifferential", "0");
        loanProductMap.put("isFloatingInterestRateCalculationAllowed", true);
        loanProductMap.put("minDifferentialLendingRate", "0");
        loanProductMap.put("defaultDifferentialLendingRate", "0");
        loanProductMap.put("maxDifferentialLendingRate", "50");
        final String responseJson = FeignRawHttpHelper.post("/loanproducts", Utils.convertToJson(loanProductMap));
        return JsonParser.parseString(responseJson).getAsJsonObject().get("resourceId").getAsInt();
    }

    private Long createAndDisburseFloatingRateLoan(Long clientId, Integer loanProductId, String date) {
        final Long loanId = applyForLoan(LoanRequestBuilders
                .legacyIndividualApplication(clientId, loanProductId.longValue(), "10000", 12, null, date).isFloatingInterestRate(true)
                .interestRateDifferential(BigDecimal.ZERO));
        approveLoan(loanId, approveLoanRequest(10000.0, date));
        disburseLoanWithNetDisbursalAmount(loanId, date, "10000");
        return loanId;
    }
}
