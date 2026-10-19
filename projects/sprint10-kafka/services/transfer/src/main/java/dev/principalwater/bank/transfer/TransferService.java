package dev.principalwater.bank.transfer;

import dev.principalwater.bank.contract.Actor;
import dev.principalwater.bank.contract.InternalOperation;
import dev.principalwater.bank.contract.OperationResult;
import dev.principalwater.bank.contract.OperationType;
import dev.principalwater.bank.contract.TransferRequest;
import dev.principalwater.bank.runtime.workflow.MoneyWorkflow;
import org.springframework.stereotype.Service;

@Service
public class TransferService {
    private final MoneyWorkflow workflow;

    public TransferService(MoneyWorkflow workflow) {
        this.workflow = workflow;
    }

    public OperationResult execute(Actor actor, TransferRequest request) {
        return workflow.execute(
                new InternalOperation(
                        actor,
                        request.operationId(),
                        OperationType.TRANSFER,
                        request.amountMinor(),
                        request.recipientId()));
    }
}
