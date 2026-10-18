package dev.principalwater.bank.cash;

import dev.principalwater.bank.contract.Actor;
import dev.principalwater.bank.contract.CashRequest;
import dev.principalwater.bank.contract.InternalOperation;
import dev.principalwater.bank.contract.OperationResult;
import dev.principalwater.bank.contract.OperationType;
import dev.principalwater.bank.runtime.error.BankError;
import dev.principalwater.bank.runtime.error.BankException;
import dev.principalwater.bank.runtime.workflow.MoneyWorkflow;
import org.springframework.stereotype.Service;

@Service
public class CashService {
    private final MoneyWorkflow workflow;

    public CashService(MoneyWorkflow workflow) {
        this.workflow = workflow;
    }

    public OperationResult execute(Actor actor, CashRequest request) {
        if (request.type() == OperationType.TRANSFER) {
            throw new BankException(BankError.INVALID_REQUEST);
        }
        return workflow.execute(
                new InternalOperation(
                        actor, request.operationId(), request.type(), request.amountMinor(), null));
    }
}
