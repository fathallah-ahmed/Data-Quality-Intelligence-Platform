package wevioo.backend.controller;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import wevioo.backend.dto.DatasetDtos.*;
import wevioo.backend.dto.UploadResponse;
import wevioo.backend.service.DatasetService;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/datasets")
public class DatasetController {
    private final DatasetService datasetService;

    public DatasetController(DatasetService datasetService) {
        this.datasetService = datasetService;
    }

    @PostMapping("/upload")
    public UploadResponse upload(
        @RequestParam(value = "name", required = false) String name,
        @RequestParam(value = "rerunExisting", defaultValue = "false") boolean rerunExisting,
        @RequestParam(value = "contextScope", required = false) String contextScope,
        @RequestParam(value = "globalDescription", required = false) String globalDescription,
        @RequestParam(value = "globalQuestion", required = false) String globalQuestion,
        @RequestParam(value = "tableDescriptionsJson", required = false) String tableDescriptionsJson,
        @RequestPart(value = "files", required = false) MultipartFile[] files,
        @RequestPart(value = "file", required = false) MultipartFile file
    ) {
        return datasetService.upload(
            name,
            files,
            file,
            rerunExisting,
            contextScope,
            globalDescription,
            globalQuestion,
            tableDescriptionsJson
        );
    }

    @GetMapping
    public List<DatasetSummary> listDatasets() {
        return datasetService.listDatasets();
    }

    @GetMapping("/{datasetId}/versions")
    public List<VersionSummary> listVersions(@PathVariable Long datasetId) {
        return datasetService.listVersions(datasetId);
    }

    @GetMapping("/{datasetId}/versions/{versionId}")
    public VersionDetails getVersionDetails(
        @PathVariable Long datasetId,
        @PathVariable Long versionId,
        @RequestParam(value = "includeRaw", defaultValue = "false") boolean includeRaw
    ) {
        return datasetService.getVersionDetails(datasetId, versionId, includeRaw);
    }

    @DeleteMapping("/{datasetId}")
    public Map<String, String> deleteDataset(@PathVariable Long datasetId) {
        datasetService.deleteDataset(datasetId);
        return Map.of("status", "deleted");
    }
}
