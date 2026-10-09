package dev.principalwater.study.mvc;

import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/files")
public class FilesController {
    private final FilesService service;

    public FilesController(FilesService service) {
        this.service = service;
    }

    @PostMapping("/upload")
    public String upload(@RequestParam("file") MultipartFile file) {
        return service.upload(file);
    }

    @GetMapping("/download/{filename}")
    public ResponseEntity<Resource> download(@PathVariable("filename") String filename) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).body(service.download(filename));
    }
}
