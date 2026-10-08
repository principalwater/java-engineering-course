package dev.principalwater.blog.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public record SearchFilter(String titleFragment, List<String> tags) {
    public SearchFilter {
        tags = List.copyOf(tags);
    }

    public static SearchFilter parse(String search) {
        var titleWords = new ArrayList<String>();
        var tags = new LinkedHashSet<String>();
        for (String word : search.strip().split("\\s+")) {
            if (word.startsWith("#")) {
                tags.add(word.substring(1));
            } else if (!word.isEmpty()) {
                titleWords.add(word);
            }
        }
        return new SearchFilter(String.join(" ", titleWords), List.copyOf(tags));
    }
}
