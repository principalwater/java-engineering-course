import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description 'Перевод возвращает остаток отправителя без остатка получателя'
    request {
        method POST()
        url '/internal/accounts/operations'
        headers {
            contentType applicationJson()
            header('Authorization', $(consumer('Bearer contract-token'), producer(execute('transferToken()'))))
        }
        body(
            actor: [issuer: 'https://fixture.bank', subject: 'subject-carol', login: 'carol'],
            operationId: '6c096269-ac7d-4aa7-8df6-8dbd094ab483', type: 'TRANSFER', amountMinor: 500,
            recipientId: 'c465e4cf-4080-480f-9fdd-5eb550be9ff9'
        )
    }
    response {
        status OK()
        headers { contentType applicationJson() }
        body(
            operationId: '6c096269-ac7d-4aa7-8df6-8dbd094ab483', status: 'SUCCEEDED', error: null,
            accountId: $(consumer('c4822d3a-33a3-4014-b19d-5a15e80ce7f3'), producer(regex(uuid()))),
            balanceMinor: 4500,
            occurredAt: $(consumer('2026-10-17T12:00:00Z'), producer(regex('[0-9]{4}-[0-9]{2}-[0-9]{2}T.*Z')))
        )
    }
}
