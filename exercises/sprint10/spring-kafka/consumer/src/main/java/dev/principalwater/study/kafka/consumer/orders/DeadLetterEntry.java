package dev.principalwater.study.kafka.consumer.orders;

import java.time.Instant;
import jakarta.persistence.*;

@Entity
@Table(name = "dead_letter_queue", indexes = @Index(name = "dlq_tail", columnList = "failed_at DESC,id"))
class DeadLetterEntry {
    @Id
    @Column(length = 300)
    private String id;
    private String msgTopic;
    private int msgPartition;
    private long msgOffset;
    @Lob
    private String msgKey;
    private String errorMessage;
    @Column(name = "failed_at", nullable = false)
    private Instant failedAt;

    protected DeadLetterEntry() { }

    DeadLetterEntry(String topic, int partition, long offset, String key, String errorMessage) {
        // shortcut: TPO устраняет повтор после SQL commit в одном lifecycle; при пересоздании добавьте topic ID.
        id = topic + ":" + partition + ":" + offset;
        msgTopic = topic;
        msgPartition = partition;
        msgOffset = offset;
        msgKey = key;
        this.errorMessage = errorMessage;
        failedAt = Instant.now();
    }

    @Override
    public String toString() {
        return "%s partition=%d offset=%d category=%s".formatted(msgTopic, msgPartition, msgOffset, errorMessage);
    }
}
