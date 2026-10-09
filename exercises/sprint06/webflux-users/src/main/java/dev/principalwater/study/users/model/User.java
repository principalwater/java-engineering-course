package dev.principalwater.study.users.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("users")
public record User(@Id Long id, String firstName, String lastName, int age, boolean active) { }
