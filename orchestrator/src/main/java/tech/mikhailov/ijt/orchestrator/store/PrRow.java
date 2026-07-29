package tech.mikhailov.ijt.orchestrator.store;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// One PR the run opened, or prepared. The row that replaces an entry of `state.prs`.
///
/// The field set is `Pr.Record`'s, whole. `GET /api/state` serves this list verbatim and the
/// dashboard renders every field of it, so dropping `body` or `labels` as "not queried" would
/// change the wire shape for a saving of two columns.
@Entity
@Table(name = "ijt_pr", indexes = @Index(name = "ix_pr_run", columnList = "run_id"))
public class PrRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "run_id", length = 64, nullable = false)
    private String runId;

    /// The unit this PR carries the tests for — `Pr.Record.file`, which is a unit key.
    @Column(name = "unit_key", length = 512)
    private String unitKey;

    @Column(name = "branch", length = 256)
    private String branch;

    @Column(name = "title", length = 1024)
    private String title;

    @Column(name = "body", length = 1_000_000)
    private String body;

    @Column(name = "labels_json", length = 4000)
    @Convert(converter = Json.StringListConverter.class)
    private List<String> labels;

    /// `github` | `local`.
    @Column(name = "mode", length = 16)
    private String mode;

    /// Exactly one of `url` and `patchPath` is ever set, and which one says whether the PR was
    /// opened or only prepared. Both start null and both stay nullable for that reason: a
    /// prepared PR with an empty-string url would read as an opened one with a broken link.
    @Column(name = "url", length = 512)
    private String url;

    @Column(name = "patch_path", length = 1024)
    private String patchPath;

    /// MILLISECONDS — `Date.now()`, not `Util.nowSec()`. The unit differs from every other
    /// timestamp in this store because it differs in `Pr.Record`, where it is serialised into
    /// the payload file beside the patch. Harmonising it here would silently move every
    /// prepared PR's creation time to 1970.
    @Column(name = "created_at")
    private long createdAt;

    protected PrRow() {} // JPA

    public PrRow(String runId, String unitKey, String branch, String title, String body,
                 List<String> labels, String mode, String url, String patchPath, long createdAt) {
        this.runId = runId;
        this.unitKey = unitKey;
        this.branch = branch;
        this.title = title;
        this.body = body;
        this.labels = labels;
        this.mode = mode;
        this.url = url;
        this.patchPath = patchPath;
        this.createdAt = createdAt;
    }

    /// The record as `state.prs` held it. The key order IS `Pr.Record`'s component order, which
    /// is the Node sidecar's insertion order and the on-disk contract of the payload file.
    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("file", unitKey);
        out.put("branch", branch);
        out.put("title", title);
        out.put("body", body);
        out.put("labels", labels);
        out.put("mode", mode);
        out.put("createdAt", createdAt);
        out.put("url", url);
        out.put("patchPath", patchPath);
        return out;
    }

    public Long getId() { return id; }

    public String getRunId() { return runId; }

    public String getUnitKey() { return unitKey; }

    public String getBranch() { return branch; }

    public String getTitle() { return title; }

    public String getBody() { return body; }

    public List<String> getLabels() { return labels; }

    public String getMode() { return mode; }

    public String getUrl() { return url; }

    public String getPatchPath() { return patchPath; }

    public long getCreatedAt() { return createdAt; }

    @Override
    public String toString() {
        return "PrRow[" + unitKey + " " + (url != null ? url : patchPath) + "]";
    }
}
