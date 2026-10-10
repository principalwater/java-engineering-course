package dev.principalwater.study.docker;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MovieController {
    private static final String GREETING = "Добро пожаловать на борт!";
    private final MovieRepository movies;

    public MovieController(MovieRepository movies) {
        this.movies = movies;
    }

    @GetMapping("/hello")
    public String hello() {
        return GREETING;
    }

    @GetMapping("/movies")
    public List<Movie> movies() {
        return movies.findAll();
    }
}
