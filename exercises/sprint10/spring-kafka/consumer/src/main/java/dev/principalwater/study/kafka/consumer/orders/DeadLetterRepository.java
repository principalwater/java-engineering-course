package dev.principalwater.study.kafka.consumer.orders;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface DeadLetterRepository extends JpaRepository<DeadLetterEntry, String> {
    List<DeadLetterEntry> findTop20ByOrderByFailedAtDescIdAsc();
}
