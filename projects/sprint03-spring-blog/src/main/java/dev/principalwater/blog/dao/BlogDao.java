package dev.principalwater.blog.dao;

import dev.principalwater.blog.model.Comment;
import dev.principalwater.blog.model.Post;
import dev.principalwater.blog.model.PostRequest;
import dev.principalwater.blog.model.SearchFilter;
import dev.principalwater.blog.model.StoredImage;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class BlogDao {
    private static final String POST_COLUMNS = """
            SELECT p.id, p.title, p.text, p.likes_count,
                   (SELECT COUNT(*) FROM comments c WHERE c.post_id = p.id) AS comments_count
            FROM posts p
            """;
    private final NamedParameterJdbcTemplate jdbc;

    public BlogDao(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Post> page(SearchFilter filter, long offset, int limit) {
        MapSqlParameterSource parameters = searchParameters(filter)
                .addValue("offset", offset).addValue("limit", limit);
        List<Post> posts = jdbc.query(POST_COLUMNS + where(filter)
                + " ORDER BY p.id DESC LIMIT :limit OFFSET :offset", parameters, this::mapPost);
        return withTags(posts);
    }

    public long count(SearchFilter filter) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM posts p" + where(filter),
                searchParameters(filter), Long.class);
    }

    public Optional<Post> get(long id) {
        return withTags(jdbc.query(POST_COLUMNS + " WHERE p.id = :id",
                Map.of("id", id), this::mapPost)).stream().findFirst();
    }

    public long insert(PostRequest post) {
        var key = new GeneratedKeyHolder();
        jdbc.update("INSERT INTO posts(title, text) VALUES(:title, :text)",
                postParameters(post), key, new String[]{"id"});
        long id = key.getKey().longValue();
        insertTags(id, post.tags());
        return id;
    }

    public int update(long id, PostRequest post) {
        int updated = jdbc.update("UPDATE posts SET title = :title, text = :text WHERE id = :id",
                postParameters(post).addValue("id", id));
        if (updated != 0) {
            jdbc.update("DELETE FROM post_tags WHERE post_id = :id", Map.of("id", id));
            insertTags(id, post.tags());
        }
        return updated;
    }

    public int delete(long id) {
        return jdbc.update("DELETE FROM posts WHERE id = :id", Map.of("id", id));
    }

    public int incrementLikes(long id) {
        return jdbc.update("UPDATE posts SET likes_count = likes_count + 1 WHERE id = :id",
                Map.of("id", id));
    }

    public long likes(long id) {
        return jdbc.queryForObject("SELECT likes_count FROM posts WHERE id = :id",
                Map.of("id", id), Long.class);
    }

    public Optional<StoredImage> image(long id) {
        return jdbc.query("SELECT image, image_content_type FROM posts WHERE id = :id AND image IS NOT NULL",
                Map.of("id", id), (rs, row) -> new StoredImage(rs.getBytes("image"),
                        rs.getString("image_content_type"))).stream().findFirst();
    }

    public int updateImage(long id, byte[] bytes, String contentType) {
        return jdbc.update("UPDATE posts SET image = :image, image_content_type = :type WHERE id = :id",
                new MapSqlParameterSource("id", id).addValue("image", bytes).addValue("type", contentType));
    }

    public List<Comment> comments(long postId) {
        return jdbc.query("SELECT id, text, post_id FROM comments WHERE post_id = :postId ORDER BY id",
                Map.of("postId", postId), this::mapComment);
    }

    public Optional<Comment> comment(long postId, long id) {
        return jdbc.query("SELECT id, text, post_id FROM comments WHERE id = :id AND post_id = :postId",
                Map.of("id", id, "postId", postId), this::mapComment).stream().findFirst();
    }

    public long insertComment(long postId, String text) {
        var key = new GeneratedKeyHolder();
        jdbc.update("INSERT INTO comments(post_id, text) VALUES(:postId, :text)",
                new MapSqlParameterSource("postId", postId).addValue("text", text), key,
                new String[]{"id"});
        return key.getKey().longValue();
    }

    public int updateComment(long postId, long id, String text) {
        return jdbc.update("UPDATE comments SET text = :text WHERE id = :id AND post_id = :postId",
                new MapSqlParameterSource("id", id).addValue("postId", postId).addValue("text", text));
    }

    public int deleteComment(long postId, long id) {
        return jdbc.update("DELETE FROM comments WHERE id = :id AND post_id = :postId",
                Map.of("id", id, "postId", postId));
    }

    private Post mapPost(ResultSet rs, int row) throws SQLException {
        return new Post(rs.getLong("id"), rs.getString("title"), rs.getString("text"), List.of(),
                rs.getLong("likes_count"), rs.getLong("comments_count"));
    }

    private Comment mapComment(ResultSet rs, int row) throws SQLException {
        return new Comment(rs.getLong("id"), rs.getString("text"), rs.getLong("post_id"));
    }

    private List<Post> withTags(List<Post> posts) {
        if (posts.isEmpty()) {
            return posts;
        }
        Map<Long, List<String>> tags = new HashMap<>();
        jdbc.query("SELECT post_id, tag FROM post_tags WHERE post_id IN (:ids) ORDER BY post_id, position",
                Map.of("ids", posts.stream().map(Post::id).toList()), rs -> {
                    tags.computeIfAbsent(rs.getLong("post_id"), ignored -> new ArrayList<>())
                            .add(rs.getString("tag"));
                });
        return posts.stream().map(post -> new Post(post.id(), post.title(), post.text(),
                tags.getOrDefault(post.id(), List.of()), post.likesCount(), post.commentsCount())).toList();
    }

    private void insertTags(long id, List<String> tags) {
        var parameters = new MapSqlParameterSource[tags.size()];
        for (int position = 0; position < tags.size(); position++) {
            parameters[position] = new MapSqlParameterSource("postId", id)
                    .addValue("tag", tags.get(position)).addValue("position", position);
        }
        if (parameters.length != 0) {
            jdbc.batchUpdate("INSERT INTO post_tags(post_id, tag, position) VALUES(:postId, :tag, :position)",
                    parameters);
        }
    }

    private MapSqlParameterSource postParameters(PostRequest post) {
        return new MapSqlParameterSource("title", post.title()).addValue("text", post.text());
    }

    private String where(SearchFilter filter) {
        var sql = new StringBuilder(" WHERE 1 = 1");
        if (!filter.titleFragment().isEmpty()) {
            sql.append(" AND LOWER(p.title) LIKE LOWER(:title) ESCAPE '!'");
        }
        for (int index = 0; index < filter.tags().size(); index++) {
            sql.append(" AND EXISTS (SELECT 1 FROM post_tags t WHERE t.post_id = p.id AND t.tag = :tag")
                    .append(index).append(")");
        }
        return sql.toString();
    }

    private MapSqlParameterSource searchParameters(SearchFilter filter) {
        String title = filter.titleFragment().replace("!", "!!").replace("%", "!%").replace("_", "!_");
        var parameters = new MapSqlParameterSource("title", "%" + title + "%");
        for (int index = 0; index < filter.tags().size(); index++) {
            parameters.addValue("tag" + index, filter.tags().get(index));
        }
        return parameters;
    }
}
