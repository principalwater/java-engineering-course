package dev.principalwater.study.books;

import org.springframework.data.annotation.Id;

public record Book(@Id Integer id, String title) { }
