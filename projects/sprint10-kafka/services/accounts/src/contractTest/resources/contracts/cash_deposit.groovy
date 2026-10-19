import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description 'Внесение наличных возвращает исходный ID команды и собственный остаток'
    request {
        method POST()
        url '/internal/accounts/operations'
        headers {
            contentType applicationJson()
            header('Authorization', $(consumer('Bearer contract-token'), producer(execute('cashToken()'))))
        }
        body(
            actor: [issuer: 'https://fixture.bank', subject: 'subject-alice', login: 'alice'],
            operationId: '1582c2bb-3d60-4aae-bb70-d30872652374',
            type: 'DEPOSIT', amountMinor: 1000, recipientId: null
        )
    }
    response {
        status OK()
        headers { contentType applicationJson() }
        body(
            operationId: '1582c2bb-3d60-4aae-bb70-d30872652374', status: 'SUCCEEDED', error: null,
            accountId: $(consumer('5808b517-d97b-4fdc-afc0-8f983fb28bc7'), producer(regex(uuid()))),
            balanceMinor: 1000,
            occurredAt: $(consumer('2026-10-17T12:00:00Z'), producer(regex('[0-9]{4}-[0-9]{2}-[0-9]{2}T.*Z')))
        )
    }
}
