package dev.principalwater.blog.service;

import dev.principalwater.blog.dao.BlogDao;
import dev.principalwater.blog.model.BlogEntity;
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
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static dev.principalwater.blog.model.BlogLimits.MAX_IMAGE_BYTES;
import static dev.principalwater.blog.model.BlogLimits.MAX_IMAGE_PIXELS;
import static dev.principalwater.blog.model.BlogLimits.MAX_PAGE_SIZE;
import static dev.principalwater.blog.model.BlogLimits.MAX_SEARCH_LENGTH;
import static dev.principalwater.blog.model.BlogLimits.MAX_TAG_COUNT;
import static dev.principalwater.blog.model.BlogLimits.MAX_TAG_LENGTH;
import static dev.principalwater.blog.model.BlogLimits.MAX_TEXT_LENGTH;
import static dev.principalwater.blog.model.BlogLimits.MAX_TITLE_LENGTH;

@Service
@Transactional(readOnly = true)
public class BlogService {
    private static final Map<String, String> IMAGE_TYPES = Map.of(
            "png", MediaType.IMAGE_PNG_VALUE, "jpeg", MediaType.IMAGE_JPEG_VALUE,
            "gif", MediaType.IMAGE_GIF_VALUE, "bmp", "image/bmp");
    private final BlogDao dao;

    public BlogService(BlogDao dao) {
        this.dao = dao;
    }

    public PostPage list(String search, int pageNumber, int pageSize) {
        if (search == null || search.length() > MAX_SEARCH_LENGTH
                || pageNumber < 1 || pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw ApiException.badRequest(RequestError.INVALID_SEARCH);
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
        return dao.get(id).orElseThrow(() -> ApiException.notFound(BlogEntity.POST));
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
        requireId(id, request.id(), RequestError.POST_ID_MISMATCH);
        requireUpdated(dao.update(id, post), BlogEntity.POST);
        return get(id);
    }

    @Transactional
    public void delete(long id) {
        requirePositive(id);
        requireUpdated(dao.delete(id), BlogEntity.POST);
    }

    @Transactional
    public long like(long id) {
        requirePositive(id);
        requireUpdated(dao.incrementLikes(id), BlogEntity.POST);
        return dao.likes(id);
    }

    public StoredImage image(long id) {
        requirePositive(id);
        return dao.image(id).orElseThrow(() -> ApiException.notFound(BlogEntity.IMAGE));
    }

    @Transactional
    public void updateImage(long id, byte[] bytes, String contentType) {
        requirePositive(id);
        String actualType = validateImage(bytes);
        requireUpdated(dao.updateImage(id, bytes, actualType), BlogEntity.POST);
    }

    public List<Comment> comments(long postId) {
        get(postId);
        return dao.comments(postId);
    }

    public Comment comment(long postId, long id) {
        requirePositive(postId);
        requirePositive(id);
        return dao.comment(postId, id).orElseThrow(() -> ApiException.notFound(BlogEntity.COMMENT));
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
        requireId(id, request.id(), RequestError.COMMENT_ID_MISMATCH);
        requireUpdated(dao.updateComment(postId, id, request.text()), BlogEntity.COMMENT);
        return comment(postId, id);
    }

    @Transactional
    public void deleteComment(long postId, long id) {
        requirePositive(postId);
        requirePositive(id);
        requireUpdated(dao.deleteComment(postId, id), BlogEntity.COMMENT);
    }

    private PostRequest validatePost(PostRequest request) {
        if (request == null || request.title() == null || request.title().isBlank()
                || request.title().length() > MAX_TITLE_LENGTH || request.text() == null || request.text().isBlank()
                || request.text().length() > MAX_TEXT_LENGTH || request.tags() == null
                || request.tags().size() > MAX_TAG_COUNT) {
            throw ApiException.badRequest(RequestError.INVALID_POST);
        }
        var tags = new LinkedHashSet<String>();
        for (String tag : request.tags()) {
            if (tag == null || tag.isBlank() || tag.length() > MAX_TAG_LENGTH
                    || tag.chars().anyMatch(Character::isWhitespace)) {
                throw ApiException.badRequest(RequestError.INVALID_TAG);
            }
            tags.add(tag);
        }
        return new PostRequest(request.id(), request.title(), request.text(), List.copyOf(tags));
    }

    private void validateComment(long postId, CommentRequest request) {
        requirePositive(postId);
        if (request == null || request.text() == null || request.text().isBlank()
                || request.text().length() > MAX_TEXT_LENGTH) {
            throw ApiException.badRequest(RequestError.INVALID_COMMENT);
        }
        requireId(postId, request.postId(), RequestError.POST_ID_MISMATCH);
    }

    private String validateImage(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_IMAGE_BYTES) {
            throw ApiException.badRequest(RequestError.INVALID_IMAGE_SIZE);
        }
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw ApiException.badRequest(RequestError.UNSUPPORTED_IMAGE_FORMAT);
            }
            var reader = readers.next();
            try {
                reader.setInput(input);
                String type = IMAGE_TYPES.get(reader.getFormatName().toLowerCase(Locale.ROOT));
                long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                if (type == null || pixels < 1 || pixels > MAX_IMAGE_PIXELS || reader.read(0) == null) {
                    throw ApiException.badRequest(RequestError.INVALID_IMAGE);
                }
                return type;
            } finally {
                reader.dispose();
            }
        } catch (IOException exception) {
            throw ApiException.badRequest(RequestError.UNREADABLE_IMAGE);
        }
    }

    private void requirePositive(long id) {
        if (id < 1) {
            throw ApiException.badRequest(RequestError.NON_POSITIVE_ID);
        }
    }

    private void requireId(long expected, Long actual, RequestError error) {
        if (actual == null || expected != actual) {
            throw ApiException.badRequest(error);
        }
    }

    private void requireUpdated(int affected, BlogEntity entity) {
        if (affected == 0) {
            throw ApiException.notFound(entity);
        }
    }
}
