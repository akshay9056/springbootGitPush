package com.avangrid.gui.avangrid_backend.service;

import com.avangrid.gui.avangrid_backend.exception.BlobAccessException;
import com.avangrid.gui.avangrid_backend.exception.InvalidRequestException;
import com.avangrid.gui.avangrid_backend.exception.RecordingNotFoundException;
import com.avangrid.gui.avangrid_backend.exception.RecordingProcessingException;
import com.avangrid.gui.avangrid_backend.model.*;
import com.avangrid.gui.avangrid_backend.repository.AzureBlobRepository;
import com.avangrid.gui.avangrid_backend.repository.RecordingsRepo;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_MP3;


import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.io.*;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class VpiRecordingService {
    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final String STATUS_SUCCESS = "200";
    private static final String MESSAGE_SUCCESS = "Success";

    private static final Set<String> ALLOWED_OPCOS = Set.of("RGE", "CMP", "NYSEG");

    private final RecordingsRepo recordingsRepo;

    private final AzureBlobRepository vpiAzureRepository;

    public VpiRecordingService(RecordingsRepo recordingsRepo,AzureBlobRepository vpiAzureRepository ) {
        this.recordingsRepo = recordingsRepo;
        this.vpiAzureRepository = vpiAzureRepository;
    }

    public VpiSearchResponse getTableData( VpiSearchRequest request){

        if (request == null) {
            throw new InvalidRequestException("Request cannot be null");
        }
        LocalDateTime fromDate = parseDateTime(request.getFrom_date());
        LocalDateTime toDate = parseDateTime(request.getTo_date());
        validateDateRange(fromDate, toDate);

        VpiSearchResponse response = new VpiSearchResponse();
        PaginationResponse pageResponse = new PaginationResponse();

        Specification<Recording> spec = buildSpecification(request);

        int pageNumber = request.getPagination() != null ? request.getPagination().getPageNumber() : 1;
        int requestedPageSize = request.getPagination() != null ? request.getPagination().getPageSize() : DEFAULT_PAGE_SIZE;
        int pageSize = requestedPageSize > 0 ? requestedPageSize : DEFAULT_PAGE_SIZE;
        int safePage = Math.max(pageNumber - 1, 0);

        Pageable pageable = PageRequest.of(safePage, pageSize, Sort.by("dateAdded").descending());
        Page<Recording> pageResult = recordingsRepo.findAll(spec, pageable);

        List<Map<String, Object>> records = new ArrayList<>();
        for (Recording rec : pageResult.getContent()) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("fileName", rec.getFileName());
            map.put("extensionNum", rec.getExtensionNum());
            map.put("objectId", rec.getObjectId());
            map.put("channelNum", rec.getChannelNum());
            map.put("aniAliDigits", rec.getAniAliDigits());
            map.put("name", rec.getName());
            map.put("dateAdded", rec.getDateAdded());
            map.put("opco",rec.getOpco());
            map.put("agentID",rec.getAgentID());
            map.put("duration",rec.getDuration());
            map.put("direction",rec.getDirection());
            records.add(map);
        }

        response.setData(records);
        response.setMessage(MESSAGE_SUCCESS);
        response.setStatus(STATUS_SUCCESS);

        pageResponse.setPageNumber(pageResult.getNumber() + 1);
        pageResponse.setPageSize( pageResult.getSize());
        pageResponse.setTotalRecords(pageResult.getTotalElements());
        pageResponse.setTotalPages(pageResult.getTotalPages());
        response.setPagination(pageResponse);

        return response;
    }

    private Specification<Recording> buildSpecification(VpiSearchRequest request) {

        Specification<Recording> spec = Specification.where(null);
        VpiFiltersRequest filters = request.getFilters();
        spec = spec.and(RecordingSpecifications.dateBetween(parseDateTime(request.getFrom_date()), parseDateTime(request.getTo_date())));

        if (filters.getFileName() != null && !filters.getFileName().isEmpty()) {
            spec = spec.and(RecordingSpecifications.fileNameContainsAny(filters.getFileName()));
        }

        if (request.getOpco() != null && !request.getOpco().isEmpty()) {
            spec = spec.and(RecordingSpecifications.containsString("opco",request.getOpco()));
        }

        if (filters.getExtensionNum() != null && !filters.getExtensionNum().isEmpty()) {
            spec = spec.and(RecordingSpecifications.extensionNumContainsAny(filters.getExtensionNum()));
        }

        if (filters.getObjectID() != null && !filters.getObjectID().isEmpty()) {
            spec = spec.and(RecordingSpecifications.objectIdContainsAny(filters.getObjectID()));
        }

        if (filters.getChannelNum() != null && !filters.getChannelNum().isEmpty()) {
            spec = spec.and(RecordingSpecifications.channelNumContainsAny( filters.getChannelNum()));
        }

        if (filters.getAniAliDigits() != null && !filters.getAniAliDigits().isEmpty()) {
            spec = spec.and(RecordingSpecifications.aniAliDigitsContainsAny( filters.getAniAliDigits()));
        }

        if (filters.getName() != null && !filters.getName().isEmpty()) {
            spec = spec.and(RecordingSpecifications.nameContainsAny(filters.getName()));
        }

        return spec;
    }

    public Recording fetchMetadata(RecordingRequest request) {
        validateRequest(request);
        List<Recording> recordingMetadata = recordingsRepo.findAllByOpcoAndFileName(request.getOpco(), request.getFilename());
        if (recordingMetadata == null || recordingMetadata.isEmpty()) {
            throw new RecordingNotFoundException("No Recordings found with OPCO=" + request.getOpco() + " and fileName=" + request.getFilename());
        }
        return recordingMetadata.getFirst();
    }

    public ResponseEntity<ByteArrayResource> getRecordingAsMp3(RecordingRequest request) {
        validateRequest(request);

        String prefix = buildBlobPrefix(request);
        List<String> blobs = getBlobNames(prefix);

        if (blobs == null || blobs.isEmpty()) {
            throw new RecordingNotFoundException(
                    "Recording not found with OPCO=" + request.getOpco()
                            + " and filename=" + request.getFilename()
            );
        }

        for (String blobName : blobs) {
            if (blobName.endsWith(".wav")) {
                return convertAndBuildResponse(request, blobName);
            }
        }

        throw new RecordingNotFoundException(
                "Recording not found with OPCO=" + request.getOpco() +
                        " and filename=" + request.getFilename()
        );
    }

    private String buildBlobPrefix(RecordingRequest request) {
        LocalDateTime fileDate = parseDateTime(request.getDate());
        return String.format(
                "%s/%d/%d/%d/%s",
                request.getOpco(),
                fileDate.getYear(),
                fileDate.getMonthValue(),
                fileDate.getDayOfMonth(),
                request.getFilename()
        );
    }

    private List<String> getBlobNames(String prefix) {
        try {
            return vpiAzureRepository.listBlobs(prefix);
        } catch (Exception e) {
            throw new BlobAccessException("Failed to list blobs for prefix: " + e.getMessage());
        }
    }

    private ResponseEntity<ByteArrayResource> convertAndBuildResponse(
            RecordingRequest request, String blobName) {

        byte[] wavData = vpiAzureRepository.getBlobContent(blobName);

        try {
            byte[] mp3Data = convertWavToMp3(wavData);

            String mp3Filename = request.getFilename().replace(".wav", ".mp3");

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType("audio/mpeg"));
            headers.setContentDispositionFormData("inline", mp3Filename);

            return ResponseEntity.ok()
                    .headers(headers)
                    .body(new ByteArrayResource(mp3Data));

        } catch (Exception e) {
            throw new RecordingProcessingException(
                    "Error converting WAV to MP3: " + e.getMessage()
            );
        }
    }

    private byte[] convertWavToMp3(byte[] wavData) throws Exception {

        Path tempDir = Files.createTempDirectory("audio_conversion_");
        String uuid = UUID.randomUUID().toString();

        Path input = tempDir.resolve("input_" + uuid + ".wav");
        Path output = tempDir.resolve("output_" + uuid + ".mp3");

        try {
            Files.write(input, wavData);

            try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(input.toFile())) {

                grabber.start();

                try (FFmpegFrameRecorder recorder = createMp3Recorder(output, grabber)) {

                    recorder.start();

                    Frame frame;

                    while ((frame = grabber.grab()) != null) {
                        if (frame.samples != null) {
                            recorder.record(frame);
                        }
                    }

                    recorder.stop();
                }

                grabber.stop();
            }

            return Files.readAllBytes(output);

        } finally {
            Files.deleteIfExists(input);
            Files.deleteIfExists(output);
            Files.deleteIfExists(tempDir);
        }
    }


    private FFmpegFrameRecorder createMp3Recorder(Path output, FFmpegFrameGrabber grabber) {
        FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(
                output.toFile(),
                grabber.getAudioChannels()
        );

        recorder.setFormat("mp3");
        recorder.setAudioCodec(AV_CODEC_ID_MP3);
        recorder.setAudioBitrate(128000);
        recorder.setSampleRate(44100);

        return recorder;
    }


    public ResponseEntity<byte[]> downloadZip(List<RecordingRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            throw new InvalidRequestException("At least one recording request is required");
        }

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ZipOutputStream zos = new ZipOutputStream(baos)) {

            for (RecordingRequest req : requests) {
                validateRequest(req);

                String prefix = buildBlobPrefix(req);
                List<String> blobs = vpiAzureRepository.listBlobs(prefix);

                if (blobs == null || blobs.isEmpty()) {
                    throw new RecordingNotFoundException("No blobs found for prefix: " + prefix);
                }

                for (String blobName : blobs) {
                    if (blobName.endsWith(".wav")) {
                        zos.putNextEntry(new ZipEntry(req.getFilename()));

                        try (InputStream blobStream = vpiAzureRepository.getBlobStream(blobName)) {
                            StreamUtils.copy(blobStream, zos);
                        }

                        zos.closeEntry();
                    }
                }
            }

            zos.finish();
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            headers.setContentDispositionFormData("attachment", "recordings.zip");

            return new ResponseEntity<>(baos.toByteArray(), headers, HttpStatus.OK);

        } catch (InvalidRequestException | RecordingNotFoundException e) {
            // rethrow domain-specific exceptions as-is
            throw e;
        } catch (IOException e) {
            throw new RecordingProcessingException("Error processing ZIP file: " + e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(("Failed to generate ZIP: " + e.getMessage()).getBytes());
        }
    }

    private void validateRequest(RecordingRequest req) {
        if (req == null) {
            throw new InvalidRequestException("Request cannot be null");
        }
        if (req.getFilename() == null || req.getFilename().isBlank()) {
            throw new InvalidRequestException("Filename is required");
        }
        if (req.getOpco() == null || req.getOpco().isBlank()) {
            throw new InvalidRequestException("OPCO is required");
        }
        if (req.getDate() == null || req.getDate().isBlank()) {
            throw new InvalidRequestException("Date is required");
        }

        if (!ALLOWED_OPCOS.contains(req.getOpco().trim())) {
            throw new InvalidRequestException("Invalid Opco");
        }
    }

    private void validateDateRange(LocalDateTime fromDate, LocalDateTime toDate) {
        if (toDate.isBefore(fromDate)) {
            throw new InvalidRequestException("Provide valid date range");
        }
    }

    private LocalDateTime parseDateTime(String dateStr) {
        try {
            return LocalDateTime.parse(dateStr, DATE_TIME_FORMATTER);
        } catch (DateTimeParseException e) {
            throw new InvalidRequestException("Invalid date format " + dateStr);
        }
    }
}
