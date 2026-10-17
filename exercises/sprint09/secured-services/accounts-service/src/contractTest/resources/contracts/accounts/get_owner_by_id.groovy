import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description 'Владелец известного счёта доступен доверенному сервису'
    request {
        method GET()
        url '/accounts/ACC-001/owner'
        headers {
            header('Authorization', $(consumer(regex('Bearer\\s+.+')), producer('Bearer test-token')))
        }
    }
    response {
        status OK()
        headers { contentType(applicationJson()) }
        body(accountId: 'ACC-001', ownerUsername: 'test-user')
    }
}
