package com.avangrid.gui.avangrid_backend.controller;

import com.avangrid.gui.avangrid_backend.model.Recording;
import com.avangrid.gui.avangrid_backend.model.RecordingRequest;
import com.avangrid.gui.avangrid_backend.model.VpiSearchRequest;
import com.avangrid.gui.avangrid_backend.repository.RecordingsRepo;
import com.avangrid.gui.avangrid_backend.model.VpiSearchResponse;

import com.avangrid.gui.avangrid_backend.service.VpiRecordingService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1")
public class MainController {

    private final RecordingsRepo repository;

    private final VpiRecordingService service;

    public MainController(RecordingsRepo repository, VpiRecordingService service) {

        this.repository = repository;
        this.service = service;
    }

    @PostMapping("/fetch-metadata")
    public ResponseEntity<VpiSearchResponse> getAllRecordings(@Valid @RequestBody VpiSearchRequest request) {

        log.info("Fetching metadata for request: {}", request);
        VpiSearchResponse response = service.getTableData(request);

        return ResponseEntity.ok(response);
    }

    @PostMapping("/recording")
    public ResponseEntity<ByteArrayResource> getRecording(
            @Valid @RequestBody RecordingRequest request) {

        log.info("Fetching recording for request: {}", request);
        return service.getRecordingAsMp3(request);
    }

    @PostMapping("/recording-metadata")
    public ResponseEntity<Recording> getMetadata(@Valid @RequestBody RecordingRequest request){

        log.info("Fetching Recording metadata for request: {}", request);
        Recording metadata = service.fetchMetadata(request);

        return ResponseEntity.ok(metadata);
    }

    @PostMapping("/download-recordings")
    public ResponseEntity<byte[]> downloadRecordings(
            @Valid @RequestBody List<RecordingRequest> requests) {

        log.info("Downloading {} recordings", requests.size());
        return service.downloadZip(requests);
    }






}
