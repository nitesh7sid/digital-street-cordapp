package com.hmlr.flows

import co.paralleluniverse.fibers.Suspendable
import com.hmlr.common.utils.FlowLogicCommonMethods
import com.hmlr.contracts.ProposedChargeAndRestrictionContract
import com.hmlr.model.ActionOnRestriction
import com.hmlr.model.DTCConsentStatus
import com.hmlr.states.LandTitleState
import com.hmlr.states.ProposedChargesAndRestrictionsState
import net.corda.core.contracts.Command
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.flows.*
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker

/**
 * Initiating flow to consent for discharge on Charge and Restriction.
 * This will be called by the seller lender to give consent on Charge and Restriction.
 */
@InitiatingFlow
@StartableByRPC
class ConsentForDischargeFlow(val landTitleStateLinearId: String) : FlowLogic<SignedTransaction>(), FlowLogicCommonMethods {

    companion object {
        object GENERATING_TRANSACTION : ProgressTracker.Step("Generating consent discharge transaction")
        object FETCHING_PROPOSED_CHARGES_AND_RESTRICTIONS_STATE : ProgressTracker.Step("Fetching the Proposed Charges And Restrictions state")
        object VERIFYING_TRANSACTION : ProgressTracker.Step("Verifying contract constraints")
        object SIGNING_TRANSACTION : ProgressTracker.Step("Signing transaction with node private key")
        object FINALISING_TRANSACTION : ProgressTracker.Step("Obtaining notary signature and recording transaction") {
            override fun childProgressTracker() = FinalityFlow.tracker()
        }

        fun tracker() = ProgressTracker(
                GENERATING_TRANSACTION,
                FETCHING_PROPOSED_CHARGES_AND_RESTRICTIONS_STATE,
                VERIFYING_TRANSACTION,
                SIGNING_TRANSACTION,
                FINALISING_TRANSACTION
        )
    }

    override val progressTracker = tracker()

    /**
     * The flows logic is encapsulated within the call() method.
     */
    @Suspendable
    override fun call(): SignedTransaction {

        // Obtain a reference to the notary
        val notary = serviceHub.firstNotary()

        // STEP: 1
        progressTracker.currentStep = GENERATING_TRANSACTION
        val tx = TransactionBuilder(notary = notary)

        // STEP: 2
        progressTracker.currentStep = FETCHING_PROPOSED_CHARGES_AND_RESTRICTIONS_STATE
        val landTitleStateAndRef = serviceHub.loadState( UniqueIdentifier.fromString(landTitleStateLinearId), LandTitleState::class.java)
        val chargeAndRestrictionStateAndRef = serviceHub.loadState(UniqueIdentifier.fromString(landTitleStateAndRef.state.data.proposedChargeOrRestrictionLinearId), ProposedChargesAndRestrictionsState::class.java)
        tx.addInputState(chargeAndRestrictionStateAndRef)

        // Add output state
        val chargesAndRestrictionsState = chargeAndRestrictionStateAndRef.state.data
        val newRestrictionStatus = serviceHub.updateRestrictions(chargesAndRestrictionsState.restrictions, ActionOnRestriction.DISCHARGE, true)
        val newChargeAndRestrictionState = chargesAndRestrictionsState.copy(status = DTCConsentStatus.CONSENT_FOR_DISCHARGE, restrictions = newRestrictionStatus)
        tx.addOutputState(newChargeAndRestrictionState, ProposedChargeAndRestrictionContract.PROPOSED_CHARGE_RESTRICTION_CONTRACT_ID)

        // Add consent discharge command
        val consentDischargeCommand = Command(ProposedChargeAndRestrictionContract.Commands.AddConsentForDischarge(), chargesAndRestrictionsState.restrictions.first().consentingParty.owningKey)
        tx.addCommand(consentDischargeCommand)

       // STEP: 3
        progressTracker.currentStep = VERIFYING_TRANSACTION
        tx.verify(serviceHub)

        // STEP: 4
        progressTracker.currentStep = SIGNING_TRANSACTION
        val signedTx = serviceHub.signInitialTransaction(tx)

        // STEP: 5
        progressTracker.currentStep = FINALISING_TRANSACTION
        return subFlow(FinalityFlow(signedTx, FINALISING_TRANSACTION.childProgressTracker()))

    }
}