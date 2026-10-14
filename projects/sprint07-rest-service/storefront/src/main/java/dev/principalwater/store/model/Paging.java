package dev.principalwater.store.model;

public record Paging(int pageSize, int pageNumber, boolean hasPrevious, boolean hasNext) {
}
