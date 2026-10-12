package dev.principalwater.blog.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public record SearchFilter(String titleFragment, List<String> tags) {
    private static final String WORD_SEPARATOR_REGEX = "\\s+";
    private static final String TAG_PREFIX = "#";
    private static final String TITLE_WORD_SEPARATOR = " ";

    public SearchFilter {
        tags = List.copyOf(tags);
    }

    public static SearchFilter parse(String search) {
        var titleWords = new ArrayList<String>();
        var tags = new LinkedHashSet<String>();
        for (String word : search.strip().split(WORD_SEPARATOR_REGEX)) {
            if (word.startsWith(TAG_PREFIX)) {
                tags.add(word.substring(TAG_PREFIX.length()));
            } else if (!word.isEmpty()) {
                titleWords.add(word);
            }
        }
        return new SearchFilter(String.join(TITLE_WORD_SEPARATOR, titleWords), List.copyOf(tags));
    }
}
