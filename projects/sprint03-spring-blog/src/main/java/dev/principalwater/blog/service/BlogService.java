package dev.principalwater.blog.service;

import dev.principalwater.blog.dao.BlogDao;
import dev.principalwater.blog.model.Comment;
import dev.principalwater.blog.model.CommentRequest;
import dev.principalwater.blog.model.Post;
import dev.principalwater.blog.model.PostPage;
import dev.principalwater.blog.model.PostRequest;
import dev.principalwater.blog.model.SearchFilter;
import dev.principalwater.blog.model.StoredImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class BlogService {
    public static final int MAX_IMAGE_BYTES = 5 * 1024 * 1024;
    private static final int MAX_TEXT_LENGTH = 1_000_000;
    private static final Map<String, String> IMAGE_TYPES = Map.of(
            "png", "image/png", "jpeg", "image/jpeg", "gif", "image/gif", "bmp", "image/bmp");
    private final BlogDao dao;

    public BlogService(BlogDao dao) {
        this.dao = dao;
    }

    public PostPage list(String search, int pageNumber, int pageSize) {
        if (search == null || search.length() > 1000 || pageNumber < 1 || pageSize < 1 || pageSize > 100) {
            throw ApiException.badRequest("Поиск до 1000 символов, номер страницы от 1, размер от 1 до 100");
        }
        var filter = SearchFilter.parse(search);
        long total = dao.count(filter);
        long lastPage = Math.max(1, (total + pageSize - 1) / pageSize);
        List<Post> posts = dao.page(filter, ((long) pageNumber - 1) * pageSize, pageSize)
                .stream().map(Post::preview).toList();
        return new PostPage(posts, pageNumber > 1, pageNumber < lastPage, lastPage);
    }

    public Post get(long id) {
        requirePositive(id);
        return dao.get(id).orElseThrow(() -> ApiException.notFound("Пост"));
    }

    @Transactional
    public Post create(PostRequest request) {
        PostRequest post = validatePost(request);
        return get(dao.insert(post));
    }

    @Transactional
    public Post update(long id, PostRequest request) {
        requirePositive(id);
        PostRequest post = validatePost(request);
        requireId(id, request.id(), "Идентификатор поста в URL и теле должен совпадать");
        requireUpdated(dao.update(id, post), "Пост");
        return get(id);
    }

    @Transactional
    public void delete(long id) {
        requirePositive(id);
        requireUpdated(dao.delete(id), "Пост");
    }

    @Transactional
    public long like(long id) {
        requirePositive(id);
        requireUpdated(dao.incrementLikes(id), "Пост");
        return dao.likes(id);
    }

    public StoredImage image(long id) {
        requirePositive(id);
        return dao.image(id).orElseThrow(() -> ApiException.notFound("Изображение"));
    }

    @Transactional
    public void updateImage(long id, byte[] bytes, String contentType) {
        requirePositive(id);
        String actualType = validateImage(bytes);
        requireUpdated(dao.updateImage(id, bytes, actualType), "Пост");
    }

    public List<Comment> comments(long postId) {
        get(postId);
        return dao.comments(postId);
    }

    public Comment comment(long postId, long id) {
        requirePositive(postId);
        requirePositive(id);
        return dao.comment(postId, id).orElseThrow(() -> ApiException.notFound("Комментарий"));
    }

    @Transactional
    public Comment addComment(long postId, CommentRequest request) {
        validateComment(postId, request);
        get(postId);
        return comment(postId, dao.insertComment(postId, request.text()));
    }

    @Transactional
    public Comment updateComment(long postId, long id, CommentRequest request) {
        requirePositive(id);
        validateComment(postId, request);
        requireId(id, request.id(), "Идентификатор комментария в URL и теле должен совпадать");
        requireUpdated(dao.updateComment(postId, id, request.text()), "Комментарий");
        return comment(postId, id);
    }

    @Transactional
    public void deleteComment(long postId, long id) {
        requirePositive(postId);
        requirePositive(id);
        requireUpdated(dao.deleteComment(postId, id), "Комментарий");
    }

    private PostRequest validatePost(PostRequest request) {
        if (request == null || request.title() == null || request.title().isBlank()
                || request.title().length() > 500 || request.text() == null || request.text().isBlank()
                || request.text().length() > MAX_TEXT_LENGTH || request.tags() == null
                || request.tags().size() > 50) {
            throw ApiException.badRequest("Нужны название до 500 символов, текст до 1000000 символов и массив до 50 тегов");
        }
        var tags = new LinkedHashSet<String>();
        for (String tag : request.tags()) {
            if (tag == null || tag.isBlank() || tag.length() > 100 || tag.chars().anyMatch(Character::isWhitespace)) {
                throw ApiException.badRequest("Тег должен содержать от 1 до 100 символов без пробелов");
            }
            tags.add(tag);
        }
        return new PostRequest(request.id(), request.title(), request.text(), List.copyOf(tags));
    }

    private void validateComment(long postId, CommentRequest request) {
        requirePositive(postId);
        if (request == null || request.text() == null || request.text().isBlank()
                || request.text().length() > MAX_TEXT_LENGTH) {
            throw ApiException.badRequest("Нужен текст комментария до 1000000 символов");
        }
        requireId(postId, request.postId(), "Идентификатор поста в URL и теле должен совпадать");
    }

    private String validateImage(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_IMAGE_BYTES) {
            throw ApiException.badRequest("Размер изображения должен быть от 1 байта до 5 МиБ");
        }
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw ApiException.badRequest("Нужно изображение PNG, JPEG, GIF или BMP");
            }
            var reader = readers.next();
            try {
                reader.setInput(input);
                String type = IMAGE_TYPES.get(reader.getFormatName().toLowerCase(Locale.ROOT));
                long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                if (type == null || pixels < 1 || pixels > 16_000_000 || reader.read(0) == null) {
                    throw ApiException.badRequest("Некорректное изображение или разрешение больше 16 мегапикселей");
                }
                return type;
            } finally {
                reader.dispose();
            }
        } catch (IOException exception) {
            throw ApiException.badRequest("Не удалось прочитать изображение");
        }
    }

    private void requirePositive(long id) {
        if (id < 1) {
            throw ApiException.badRequest("Идентификатор должен быть положительным");
        }
    }

    private void requireId(long expected, Long actual, String message) {
        if (actual == null || expected != actual) {
            throw ApiException.badRequest(message);
        }
    }

    private void requireUpdated(int affected, String entity) {
        if (affected == 0) {
            throw ApiException.notFound(entity);
        }
    }
}
