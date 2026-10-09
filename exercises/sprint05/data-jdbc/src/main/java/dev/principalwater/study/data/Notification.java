package dev.principalwater.study.data;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("notification")
public record Notification(@Id @Column("id") Long id, @Column("message") String message) {}
