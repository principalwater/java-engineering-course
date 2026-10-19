import org.springframework.cloud.contract.spec.Contract
Contract.make {
    label 'transfer_notification'
    input { triggeredBy('publishNotification()') }
    outputMessage {
        sentTo 'bank.transfer.notifications.v1'
        headers { header('kafka_messageKey', '5808b517-d97b-4fdc-afc0-8f983fb28bc7') }
        body([eventId: $(consumer('49a83c26-7e3d-4142-a1be-05d8f6088aaf'), producer(regex('[0-9a-f-]{36}'))), ownerId: '5808b517-d97b-4fdc-afc0-8f983fb28bc7', type: 'TRANSFER_SUCCEEDED', amountMinor: 500, balanceMinor: 4500, occurredAt: $(consumer('2026-10-17T12:00:00Z'), producer(regex('.*Z')))])
    }
}
