"use strict";

const $ = (id) => document.getElementById(id);

const TERMINAL = new Set(["completed", "failed", "cancelled"]);
const ACTIVE = new Set([
  "queued",
  "probing",
  "planning",
  "processing",
  "validating",
  "finalizing",
  "cancelling",
]);

const STATUS_LABELS = {
  queued: "Preparing",
  probing: "Inspecting source",
  planning: "Selecting segments",
  processing: "Encoding",
  validating: "Validating output",
  finalizing: "Saving",
  cancelling: "Cancelling",
  completed: "Completed",
  failed: "Failed",
  cancelled: "Cancelled",
};

const state = {
  system: null,
  engineReady: false,
  metadata: null,
  retention: 50,
  retentionMode: "preset",
  plan: null,
  planSeq: 0,
  planTimer: null,
  job: null,
  pollTimer: null,
  pollGeneration: 0,
  outputTouched: false,
  inspectorBusy: false,
  picker: {
    mode: "file",
    data: null,
    showAll: false,
  },
  jobs: [],
  notifiedJobs: new Set(),
};

document.addEventListener("DOMContentLoaded", () => {
  bindEvents();
  init().catch((error) => {
    showAlert(error.message || String(error), error.hint);
  });
});

function bindEvents() {
  $("browseInput").addEventListener("click", () => openPicker("file"));
  $("inspectButton").addEventListener("click", () => inspectInput());
  $("inputPath").addEventListener("keydown", (event) => {
    if (event.key === "Enter") {
      event.preventDefault();
      inspectInput();
    }
  });
  $("inputPath").addEventListener("change", () => {
    if (!state.inspectorBusy && $("inputPath").value.trim()) {
      updateStartState();
    }
  });
  $("browseOutput").addEventListener("click", () => openPicker("directory"));
  $("outputDir").addEventListener("input", () => {
    state.outputTouched = true;
    persistOutputDir();
    updateStartState();
  });

  document.querySelectorAll("[data-retention]").forEach((button) => {
    button.addEventListener("click", () => {
      const value = button.dataset.retention;
      if (value === "custom") {
        setRetentionMode("custom", true);
        updatePlanSoon();
      } else {
        setRetentionMode("preset");
        setRetentionValue(parseFloat(value));
        updatePlanSoon();
      }
    });
  });

  $("customPercent").addEventListener("input", () => {
    setRetentionMode("custom");
    updatePlanSoon();
  });
  $("customPercent").addEventListener("change", () => {
    setRetentionMode("custom");
    updatePlanSoon();
  });

  $("startButton").addEventListener("click", startExport);
  $("cancelButton").addEventListener("click", cancelExport);
  $("copyPathButton").addEventListener("click", copyOutputPath);
  $("newJobButton").addEventListener("click", clearJobPanel);
  $("alertClose").addEventListener("click", clearAlert);
  $("refreshHistory").addEventListener("click", () => loadJobs(true));

  $("pickerClose").addEventListener("click", closePicker);
  $("pickerCancel").addEventListener("click", closePicker);
  $("pickerUp").addEventListener("click", () => {
    if (state.picker.data && state.picker.data.parent) {
      navigatePicker(state.picker.data.parent);
    }
  });
  $("pickerGo").addEventListener("click", goToPickerPath);
  $("pickerPath").addEventListener("keydown", (event) => {
    if (event.key === "Enter") {
      event.preventDefault();
      goToPickerPath();
    }
  });
  $("pickerUseFolder").addEventListener("click", usePickerFolder);
  $("showAllFiles").addEventListener("change", () => {
    state.picker.showAll = $("showAllFiles").checked;
    renderPicker();
  });
  document.querySelectorAll("[data-close-picker]").forEach((element) => {
    element.addEventListener("click", closePicker);
  });
  document.addEventListener("keydown", (event) => {
    if (event.key === "Escape" && !$("pickerModal").classList.contains("hidden")) {
      closePicker();
    }
  });
}

async function init() {
  const storedOutput = safeStorageGet("shoo-cut.outputDir");
  if (storedOutput) {
    $("outputDir").value = storedOutput;
    state.outputTouched = true;
  }

  try {
    const system = await api("/api/system");
    state.system = system;
    renderEngineStatus(system.tools);
    if (!storedOutput && !$("outputDir").value) {
      $("outputDir").value = system.default_output_dir || "";
    }
  } catch (error) {
    renderEngineStatus(null);
    showAlert(error.message, error.hint);
  }

  updateRetentionButtons();
  updateEstimate();
  updateStartState();
  await loadJobs(false);
}

async function api(path, options = {}) {
  const init = {
    cache: "no-store",
    ...options,
  };
  if (init.body !== undefined && typeof init.body !== "string") {
    init.headers = {
      "Content-Type": "application/json",
      ...(init.headers || {}),
    };
    init.body = JSON.stringify(init.body);
  }
  const response = await fetch(path, init);
  let data = null;
  try {
    data = await response.json();
  } catch (_) {
    // Non-JSON error from an unexpected server failure.
  }
  if (!response.ok) {
    const error = new Error(
      (data && data.error) || `Request failed with HTTP ${response.status}.`
    );
    error.status = response.status;
    error.hint = data && data.hint;
    throw error;
  }
  return data;
}

function renderEngineStatus(tools) {
  const badge = $("engineBadge");
  const dot = $("engineDot");
  const text = $("engineText");
  dot.className = "status-dot";
  badge.title = "";
  if (!tools || !tools.ffmpeg_available || !tools.ffprobe_available) {
    state.engineReady = false;
    dot.classList.add("error");
    text.textContent = "FFmpeg not available";
    badge.title = (tools && tools.error) || "Install FFmpeg to enable exports.";
    return;
  }
  if (!tools.encoders_ok) {
    state.engineReady = false;
    dot.classList.add("warning");
    text.textContent = "FFmpeg encoders missing";
    badge.title = tools.error || "libx264 and AAC are required.";
    return;
  }
  state.engineReady = true;
  dot.classList.add("ok");
  let version = "FFmpeg";
  if (tools.ffmpeg_version) {
    const match = tools.ffmpeg_version.match(/ffmpeg version (\S+)/i);
    if (match) version = `FFmpeg ${match[1]}`;
  }
  text.textContent = `${version} ready`;
  badge.title = tools.ffmpeg_version || "FFmpeg is ready";
}

function setInspectorBusy(busy) {
  state.inspectorBusy = busy;
  $("inspectButton").disabled = busy;
  $("inputPath").disabled = busy;
  $("browseInput").disabled = busy;
  $("inspectButton").textContent = busy ? "Inspecting…" : "Inspect";
}

async function inspectInput(explicitPath) {
  if (state.inspectorBusy || isJobActive()) return;
  const path = (explicitPath !== undefined ? explicitPath : $("inputPath").value).trim();
  if (!path) {
    showAlert("Choose a video file first.", "Enter a path or use the Browse button.");
    return;
  }
  clearAlert();
  setInspectorBusy(true);
  try {
    const result = await api("/api/probe", {
      method: "POST",
      body: { path },
    });
    state.metadata = result.metadata;
    $("inputPath").value = result.metadata.path;
    if (!state.outputTouched || !$("outputDir").value.trim()) {
      $("outputDir").value = result.suggested_output_dir || "";
      persistOutputDir();
    }
    renderMetadata(result.metadata);
    state.plan = null;
    updateEstimate();
    updatePlan();
  } catch (error) {
    state.metadata = null;
    state.plan = null;
    renderMetadata(null);
    updateEstimate();
    updateTimeline(null);
    updateStartState();
    showAlert(error.message, error.hint);
  } finally {
    setInspectorBusy(false);
    updateStartState();
  }
}

function renderMetadata(metadata) {
  const empty = $("sourceEmpty");
  const panel = $("metadataPanel");
  if (!metadata) {
    empty.classList.remove("hidden");
    panel.classList.add("hidden");
    $("metaSegments").textContent = "—";
    return;
  }
  empty.classList.add("hidden");
  panel.classList.remove("hidden");
  $("sourceFilename").textContent = metadata.filename;
  $("sourceFilename").title = metadata.filename;
  $("sourcePath").textContent = metadata.path;
  $("sourcePath").title = metadata.path;
  $("sourceContainer").textContent = metadata.container;
  $("metaDuration").textContent =
    metadata.duration_label || formatDuration(metadata.duration);
  $("metaResolution").textContent = metadata.resolution;
  $("metaFps").textContent = metadata.fps ? `${trimNumber(metadata.fps)} fps` : "Unknown / VFR";
  $("metaVideoCodec").textContent = (metadata.video_codec || "unknown").toUpperCase();
  $("metaAudio").textContent = metadata.has_audio
    ? `${(metadata.audio_codec || "audio").toUpperCase()} · ${metadata.audio_channels || "?"} ch${
        metadata.audio_sample_rate ? ` · ${trimNumber(metadata.audio_sample_rate / 1000)} kHz` : ""
      }`
    : "None — video-only export";
  $("metaSize").textContent = formatBytes(metadata.size_bytes);
  $("metaBitrate").textContent = metadata.bitrate
    ? `${trimNumber(metadata.bitrate / 1000000)} Mbps`
    : "Unknown";

  const warnings = [];
  if (!metadata.has_audio) {
    warnings.push("This recording has no audio track. The exported MP4 will be video-only.");
  }
  if (metadata.fps && metadata.fps > 60) {
    warnings.push("Source is above 60 fps; the export will be normalised to 60 fps.");
  }
  if (metadata.rotation) {
    warnings.push(`Source rotation (${metadata.rotation}°) will be applied by FFmpeg.`);
  }
  const warningLine = $("sourceWarning");
  if (warnings.length) {
    warningLine.textContent = warnings.join(" ");
    warningLine.classList.remove("hidden");
  } else {
    warningLine.classList.add("hidden");
  }
}

function getRetentionPercent() {
  if (state.retentionMode === "custom") {
    const raw = $("customPercent").value.trim();
    if (raw === "") return null;
    const value = Number(raw);
    if (!Number.isFinite(value) || value <= 0 || value > 100) return null;
    return value;
  }
  return state.retention;
}

function setRetentionMode(mode, focusInput = false) {
  state.retentionMode = mode;
  $("customRetentionRow").classList.toggle("hidden", mode !== "custom");
  if (mode === "custom" && focusInput) {
    $("customPercent").focus();
    $("customPercent").select();
  }
  updateRetentionButtons();
  updateStartState();
}

function setRetentionValue(value) {
  if (!Number.isFinite(value)) return;
  state.retention = value;
  updateRetentionButtons();
  updateStartState();
}

function updateRetentionButtons() {
  document.querySelectorAll("[data-retention]").forEach((button) => {
    const raw = button.dataset.retention;
    let active = false;
    if (raw === "custom") {
      active = state.retentionMode === "custom";
    } else if (state.retentionMode === "preset") {
      active = Math.abs(parseFloat(raw) - state.retention) < 0.0001;
    }
    button.classList.toggle("active", active);
    button.setAttribute("aria-pressed", active ? "true" : "false");
  });
}

function updatePlanSoon() {
  if (!state.metadata) {
    updateEstimate();
    updateStartState();
    return;
  }
  window.clearTimeout(state.planTimer);
  state.planTimer = window.setTimeout(updatePlan, 120);
  updateEstimate();
}

async function updatePlan() {
  if (!state.metadata) return;
  const percent = getRetentionPercent();
  updateEstimate();
  if (percent === null) {
    state.plan = null;
    updateTimeline(null);
    updateStartState();
    return;
  }
  const sequence = ++state.planSeq;
  try {
    const response = await api("/api/plan", {
      method: "POST",
      body: {
        duration: state.metadata.duration,
        retention_percent: percent,
      },
    });
    if (sequence !== state.planSeq) return;
    state.plan = response.plan;
    renderPlan(response.plan);
  } catch (error) {
    if (sequence !== state.planSeq) return;
    state.plan = null;
    updateTimeline(null);
    $("timelineEmpty").textContent = error.message;
    updateEstimate();
    updateStartState();
  }
}

function renderPlan(plan) {
  if (!plan) {
    updateTimeline(null);
    updateEstimate();
    updateStartState();
    return;
  }
  state.plan = plan;
  $("estimateSource").textContent = plan.source_duration_label;
  $("estimateKept").textContent = plan.selected_duration_label;
  $("estimateSegments").textContent = String(plan.segment_count);
  $("metaSegments").textContent = String(plan.segment_count);
  $("planStrategyLabel").textContent = `· ${plan.segment_count} segment${plan.segment_count === 1 ? "" : "s"}`;
  renderTimeline(plan);
  renderSegmentList(plan);
  updateStartState();
}

function updateTimeline(plan) {
  const timeline = $("timeline");
  timeline.querySelectorAll(".timeline-segment").forEach((node) => node.remove());
  const empty = $("timelineEmpty");
  if (!plan || !plan.segments || !plan.segments.length) {
    empty.classList.remove("hidden");
    if (!empty.textContent) empty.textContent = "The selected timeline will appear here.";
    return;
  }
  empty.classList.add("hidden");
  const total = plan.source_duration || 1;
  plan.segments.forEach((segment) => {
    const node = document.createElement("div");
    node.className = "timeline-segment";
    const left = Math.max(0, Math.min(100, (segment.start / total) * 100));
    const width = Math.max(0.12, Math.min(100 - left, (segment.duration / total) * 100));
    node.style.left = `${left}%`;
    node.style.width = `${width}%`;
    node.title = `Segment ${segment.index + 1}: ${segment.start_label} → ${segment.end_label} (${segment.duration_label})`;
    timeline.appendChild(node);
  });
}

function renderSegmentList(plan) {
  const list = $("segmentList");
  list.replaceChildren();
  plan.segments.forEach((segment) => {
    const item = document.createElement("li");
    const index = document.createElement("span");
    index.className = "seg-index";
    index.textContent = String(segment.index + 1).padStart(2, "0");
    const range = document.createElement("span");
    range.textContent = `${segment.start_label} → ${segment.end_label}`;
    const duration = document.createElement("span");
    duration.className = "seg-duration";
    duration.textContent = segment.duration_label;
    item.append(index, range, duration);
    list.appendChild(item);
  });
  const details = $("planDetails");
  if (plan.segment_count <= 3) details.removeAttribute("open");
}

function updateEstimate() {
  if (!state.metadata) {
    $("estimateSource").textContent = "—";
    $("estimateKept").textContent = "—";
    $("estimateSegments").textContent = "—";
    return;
  }
  $("estimateSource").textContent =
    state.metadata.duration_label || formatDuration(state.metadata.duration);
  const percent = getRetentionPercent();
  if (percent === null) {
    $("estimateKept").textContent = "Enter 0–100";
    $("estimateSegments").textContent = "—";
    return;
  }
  if (state.plan) {
    $("estimateKept").textContent = state.plan.selected_duration_label;
    $("estimateSegments").textContent = String(state.plan.segment_count);
  } else {
    const estimated = state.metadata.duration * (percent / 100);
    $("estimateKept").textContent = formatDuration(estimated);
    $("estimateSegments").textContent = "…";
  }
}

function isJobActive() {
  return !!(state.job && state.job.active);
}

function updateStartState() {
  const percent = getRetentionPercent();
  const canStart =
    !!state.metadata &&
    !!state.plan &&
    percent !== null &&
    !!$("outputDir").value.trim() &&
    state.engineReady &&
    !state.inspectorBusy &&
    !isJobActive();
  const button = $("startButton");
  button.disabled = !canStart;
  $("startSpinner").classList.toggle("hidden", !isJobActive());
  $("startButtonLabel").textContent = isJobActive() ? "Export in progress…" : "Start cutting";
  if (!state.metadata && !isJobActive()) {
    button.title = "Choose and inspect a video first.";
  } else if (!state.engineReady) {
    button.title = "FFmpeg with libx264 and AAC is required.";
  } else if (percent === null) {
    button.title = "Enter a custom retention between 0 and 100.";
  } else if (!state.plan) {
    button.title = "Waiting for the segment plan.";
  } else if (isJobActive()) {
    button.title = "Another export is running.";
  } else {
    button.removeAttribute("title");
  }
}

function setFormDisabled(disabled) {
  [
    "inputPath",
    "browseInput",
    "inspectButton",
    "outputDir",
    "browseOutput",
    "customPercent",
  ].forEach((id) => {
    $(id).disabled = disabled;
  });
  document.querySelectorAll("[data-retention]").forEach((button) => {
    button.disabled = disabled;
  });
  if (!disabled) {
    $("inspectButton").textContent = "Inspect";
  }
  updateStartState();
}

async function startExport() {
  clearAlert();
  if (!state.metadata) {
    showAlert("Choose a source video first.");
    return;
  }
  const percent = getRetentionPercent();
  if (percent === null) {
    showAlert("Enter a custom retention between 0 and 100.");
    return;
  }
  const outputDir = $("outputDir").value.trim();
  if (!outputDir) {
    showAlert("Choose an output folder.");
    return;
  }
  if (!state.engineReady) {
    showAlert("FFmpeg is not ready.", "Install FFmpeg with libx264 and AAC support.");
    return;
  }

  requestNotificationPermission();
  $("startButton").disabled = true;
  $("startSpinner").classList.remove("hidden");
  $("startButtonLabel").textContent = "Starting…";
  try {
    const response = await api("/api/jobs", {
      method: "POST",
      body: {
        input_path: state.metadata.path,
        output_dir: outputDir,
        retention_percent: percent,
      },
    });
    state.job = response.job;
    renderJob(response.job);
    setFormDisabled(true);
    $("jobPanel").classList.remove("hidden");
    $("jobPanel").scrollIntoView({ behavior: "smooth", block: "nearest" });
    startPolling();
    loadJobs(false);
  } catch (error) {
    $("startSpinner").classList.add("hidden");
    showAlert(error.message, error.hint);
    updateStartState();
    if (error.status === 409) {
      await loadJobs(false);
    }
  }
}

function startPolling() {
  stopPolling();
  if (!state.job || !state.job.id) return;
  const jobId = state.job.id;
  const generation = state.pollGeneration;
  const poll = async () => {
    if (generation !== state.pollGeneration) return;
    try {
      const response = await api(`/api/jobs/${encodeURIComponent(jobId)}`);
      if (generation !== state.pollGeneration) return;
      if (!state.job || state.job.id !== jobId) return;
      const wasActive = isJobActive();
      state.job = response.job;
      renderJob(response.job);
      if (TERMINAL.has(response.job.status)) {
        stopPolling();
        setFormDisabled(false);
        if (wasActive) handleTerminalNotification(response.job);
        loadJobs(false);
      } else {
        state.pollTimer = window.setTimeout(poll, 750);
      }
    } catch (error) {
      if (generation !== state.pollGeneration) return;
      stopPolling();
      setFormDisabled(false);
      showAlert(`Lost contact with the export job: ${error.message}`, error.hint);
    }
  };
  state.pollTimer = window.setTimeout(poll, 250);
}

function stopPolling() {
  state.pollGeneration += 1;
  if (state.pollTimer) {
    window.clearTimeout(state.pollTimer);
    state.pollTimer = null;
  }
}

function renderJob(job) {
  if (!job) return;
  state.job = job;
  const panel = $("jobPanel");
  panel.classList.remove(
    "hidden",
    "status-completed",
    "status-failed",
    "status-cancelled",
    "status-processing"
  );
  panel.classList.add(`status-${job.status}`);

  const title = STATUS_LABELS[job.status] || "Export";
  $("jobHeading").textContent =
    job.status === "completed"
      ? "Export complete"
      : job.status === "failed"
        ? "Export failed"
        : job.status === "cancelled"
          ? "Export cancelled"
          : title;
  $("jobMessage").textContent = job.message || "";
  $("jobDot").className = `status-dot large ${job.status}`;

  const percent = Number(job.progress) || 0;
  $("progressFill").style.width = `${Math.max(0, Math.min(100, percent))}%`;
  $("progressTrack").setAttribute("aria-valuenow", String(Math.round(percent)));
  $("progressPercent").textContent = `${percent.toFixed(percent % 1 ? 1 : 0)}%`;
  $("jobElapsed").textContent = `${formatElapsed(job.elapsed_seconds || 0)} elapsed`;
  const speedParts = [];
  if (job.encode_percent !== null && job.encode_percent !== undefined) {
    speedParts.push(`encode ${Number(job.encode_percent).toFixed(0)}%`);
  }
  if (job.speed) speedParts.push(`FFmpeg speed ${job.speed}`);
  $("jobSpeed").textContent = speedParts.join(" · ");

  const active = !!job.active;
  const cancelButton = $("cancelButton");
  cancelButton.classList.toggle("hidden", !active);
  cancelButton.disabled = job.status === "cancelling";
  cancelButton.textContent = job.status === "cancelling" ? "Cancelling…" : "Cancel export";

  const completion = $("completionBox");
  if (job.status === "completed" && job.output_path) {
    completion.classList.remove("hidden");
    $("outputPath").textContent = job.output_path;
    $("outputPath").title = job.output_path;
    const outputMeta = job.validation && job.validation.output_metadata;
    const pieces = [];
    if (job.plan) pieces.push(`${job.plan.selected_duration_label} retained`);
    pieces.push("1280×720");
    if (outputMeta) {
      const audio = outputMeta.has_audio ? "AAC" : "no audio";
      pieces.push(`${outputMeta.video_codec.toUpperCase()} / ${audio}`);
      pieces.push(formatDuration(outputMeta.duration));
    }
    $("completionDetails").textContent = pieces.join(" · ");
  } else {
    completion.classList.add("hidden");
    $("outputPath").textContent = "—";
  }

  const errorBox = $("jobErrorBox");
  if (job.status === "failed") {
    errorBox.classList.remove("hidden");
    $("jobErrorText").textContent = job.error || "The export failed. Check the log below.";
  } else {
    errorBox.classList.add("hidden");
    $("jobErrorText").textContent = "";
  }

  const log = $("jobLog");
  const logText = (job.log_tail || []).join("\n");
  const wasNearBottom =
    log.scrollTop + log.clientHeight >= log.scrollHeight - 40 || log.scrollTop === 0;
  log.textContent = logText || "Waiting for log output…";
  if (wasNearBottom) log.scrollTop = log.scrollHeight;

  setFormDisabled(active);
  updateStartState();
}

function handleTerminalNotification(job) {
  if (job.status === "completed") {
    const message = job.output_path || "The MP4 is ready.";
    showToast("Export complete. The MP4 is ready.", "success", 6000);
    notifyUser("Shoo Auto Cut: export complete", basename(message));
  } else if (job.status === "failed") {
    showToast("Export failed. See the job panel for details.", "error", 6000);
  } else if (job.status === "cancelled") {
    showToast("Export cancelled. Temporary files were removed.", "info", 5000);
  }
}

async function cancelExport() {
  if (!state.job || !state.job.active) return;
  $("cancelButton").disabled = true;
  $("cancelButton").textContent = "Cancelling…";
  try {
    const response = await api(`/api/jobs/${encodeURIComponent(state.job.id)}/cancel`, {
      method: "POST",
      body: {},
    });
    state.job = response.job;
    renderJob(response.job);
    startPolling();
  } catch (error) {
    $("cancelButton").disabled = false;
    showAlert(error.message, error.hint);
  }
}

function clearJobPanel() {
  if (isJobActive()) return;
  stopPolling();
  state.job = null;
  $("jobPanel").classList.add("hidden");
  setFormDisabled(false);
  updateStartState();
  window.scrollTo({ top: 0, behavior: "smooth" });
}

async function copyOutputPath() {
  const path = state.job && state.job.output_path;
  if (!path) return;
  const copied = await copyText(path);
  if (copied) {
    showToast("Output path copied.", "success", 3000);
  } else {
    showAlert("Could not copy automatically.", path);
  }
}

async function copyText(text) {
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text);
      return true;
    }
  } catch (_) {
    // Fall through to the selection-based fallback.
  }
  try {
    const area = document.createElement("textarea");
    area.value = text;
    area.setAttribute("readonly", "");
    area.style.position = "fixed";
    area.style.opacity = "0";
    document.body.appendChild(area);
    area.select();
    const ok = document.execCommand("copy");
    area.remove();
    return ok;
  } catch (_) {
    return false;
  }
}

function requestNotificationPermission() {
  if (!("Notification" in window)) return;
  if (Notification.permission === "default") {
    try {
      Notification.requestPermission().catch(() => {});
    } catch (_) {
      // Older browsers return undefined instead of a Promise.
    }
  }
}

function notifyUser(title, body) {
  if (!("Notification" in window) || Notification.permission !== "granted") return;
  try {
    const notification = new Notification(title, {
      body,
      tag: "shoo-cut-export",
    });
    window.setTimeout(() => notification.close(), 9000);
  } catch (_) {
    // The in-page toast remains the primary notification.
  }
}

async function loadJobs(showError) {
  try {
    const response = await api("/api/jobs");
    state.jobs = response.jobs || [];
    renderHistory();
    const active = state.jobs.find((job) => job.active);
    if (active && (!state.job || state.job.id !== active.id)) {
      const full = await api(`/api/jobs/${encodeURIComponent(active.id)}`);
      applyJobToForm(full.job, true);
    }
  } catch (error) {
    if (showError) showAlert(error.message, error.hint);
  }
}

function applyJobToForm(job, resume) {
  if (!job) return;
  if (job.metadata) {
    state.metadata = job.metadata;
    $("inputPath").value = job.metadata.path;
    renderMetadata(job.metadata);
  }
  if (job.plan) {
    renderPlan(job.plan);
  }
  if (typeof job.retention_percent === "number") {
    state.retention = job.retention_percent;
    const isPreset = [25, 50, 75, 100].some(
      (value) => Math.abs(value - job.retention_percent) < 0.0001
    );
    state.retentionMode = isPreset ? "preset" : "custom";
    if (!isPreset) $("customPercent").value = String(job.retention_percent);
    $("customRetentionRow").classList.toggle("hidden", isPreset);
    updateRetentionButtons();
  }
  if (job.output_dir) {
    $("outputDir").value = job.output_dir;
    state.outputTouched = true;
  }
  state.job = job;
  renderJob(job);
  $("jobPanel").classList.remove("hidden");
  if (resume && job.active) {
    setFormDisabled(true);
    startPolling();
    $("jobPanel").scrollIntoView({ behavior: "smooth", block: "nearest" });
  } else if (job.active) {
    setFormDisabled(true);
    startPolling();
  } else {
    setFormDisabled(false);
  }
  updateEstimate();
  updateStartState();
}

function renderHistory() {
  const panel = $("historyPanel");
  const list = $("historyList");
  const jobs = state.jobs.slice(0, 8);
  list.replaceChildren();
  if (!jobs.length) {
    panel.classList.add("hidden");
    return;
  }
  panel.classList.remove("hidden");
  jobs.forEach((job) => {
    const item = document.createElement("button");
    item.type = "button";
    item.className = "history-item";
    item.title = `${job.input_path}\n${job.output_path || job.message || ""}`;
    const dot = document.createElement("span");
    dot.className = `status-dot ${job.status}`;
    const copy = document.createElement("span");
    const name = document.createElement("span");
    name.className = "history-name";
    name.textContent = job.metadata ? job.metadata.filename : basename(job.input_path);
    const sub = document.createElement("span");
    sub.className = "history-sub";
    const statusText = STATUS_LABELS[job.status] || job.status;
    const kept = job.plan ? job.plan.selected_duration_label : "—";
    sub.textContent = `${job.retention_percent}% · ${statusText} · ${kept}`;
    copy.append(name, sub);
    const time = document.createElement("time");
    time.dateTime = job.created_at || "";
    time.textContent = relativeTime(job.created_at);
    item.append(dot, copy, time);
    item.addEventListener("click", () => openHistoryJob(job.id));
    list.appendChild(item);
  });
}

async function openHistoryJob(jobId) {
  if (isJobActive() && state.job && state.job.id !== jobId) {
    showToast("Finish or cancel the current export before opening another job.", "error");
    return;
  }
  try {
    const response = await api(`/api/jobs/${encodeURIComponent(jobId)}`);
    applyJobToForm(response.job, true);
  } catch (error) {
    showAlert(error.message, error.hint);
  }
}

function showAlert(message, hint) {
  $("alertTitle").textContent = "Something went wrong";
  $("alertMessage").textContent = message || "Unknown error.";
  const hintNode = $("alertHint");
  if (hint) {
    hintNode.textContent = hint;
    hintNode.classList.remove("hidden");
  } else {
    hintNode.textContent = "";
    hintNode.classList.add("hidden");
  }
  $("alertBox").classList.remove("hidden");
  $("alertBox").classList.remove("success");
  window.scrollTo({ top: 0, behavior: "smooth" });
}

function clearAlert() {
  $("alertBox").classList.add("hidden");
}

function showToast(message, kind, duration = 4000) {
  const region = $("toastRegion");
  const toast = document.createElement("div");
  toast.className = `toast ${kind || "info"}`;
  toast.textContent = message;
  region.appendChild(toast);
  window.setTimeout(() => {
    toast.style.opacity = "0";
    window.setTimeout(() => toast.remove(), 200);
  }, duration);
}

// ---------------------------------------------------------------------------
// File / folder picker
// ---------------------------------------------------------------------------

function openPicker(mode) {
  if (isJobActive()) {
    showToast("Wait for the current export to finish.", "error");
    return;
  }
  state.picker.mode = mode;
  state.picker.showAll = false;
  $("showAllFiles").checked = false;
  $("pickerUseFolder").classList.toggle("hidden", mode !== "directory");
  $("pickerTitle").textContent = mode === "file" ? "Choose a video" : "Choose an output folder";
  $("pickerSubtitle").textContent =
    mode === "file"
      ? "Files are read directly from this computer. Nothing is uploaded."
      : "The finished MP4 will be saved in the selected folder.";
  $("pickerOptions").classList.toggle("hidden", mode !== "file");
  $("showAllFiles").closest("label").classList.toggle("hidden", mode !== "file");
  hidePickerError();
  $("pickerModal").classList.remove("hidden");
  $("pickerModal").setAttribute("aria-hidden", "false");
  document.body.style.overflow = "hidden";

  const inputValue = $("inputPath").value.trim();
  const outputValue = $("outputDir").value.trim();
  const rawStart =
    mode === "file"
      ? inputValue
        ? parentPath(inputValue)
        : state.system && state.system.home
      : outputValue
        ? parentPath(outputValue)
        : state.system && state.system.home;
  const fallback = (state.system && state.system.home) || "";
  navigatePicker(rawStart || fallback).then((ok) => {
    if (!ok) navigatePicker(fallback);
  });
  window.setTimeout(() => $("pickerPath").focus(), 30);
}

function closePicker() {
  $("pickerModal").classList.add("hidden");
  $("pickerModal").setAttribute("aria-hidden", "true");
  document.body.style.overflow = "";
  hidePickerError();
}

async function navigatePicker(path) {
  hidePickerError();
  try {
    const response = await api(`/api/fs?path=${encodeURIComponent(path || "")}`);
    state.picker.data = response;
    renderPicker();
    return true;
  } catch (error) {
    showPickerError(error.message);
    return false;
  }
}

function renderPicker() {
  const data = state.picker.data;
  if (!data) return;
  const isDirectoryMode = state.picker.mode === "directory";
  $("pickerPath").value = data.current;
  $("pickerCurrent").textContent = data.current;
  $("pickerCurrent").title = data.current;
  $("pickerUp").disabled = !data.parent;

  const shortcuts = $("pickerShortcuts");
  shortcuts.replaceChildren();
  (data.shortcuts || []).forEach((shortcut) => {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "shortcut";
    button.textContent = shortcut.label;
    button.title = shortcut.path;
    button.addEventListener("click", () => navigatePicker(shortcut.path));
    shortcuts.appendChild(button);
  });

  const list = $("pickerList");
  list.replaceChildren();
  const entries = (data.entries || []).filter((entry) => {
    if (entry.kind === "directory") return true;
    if (isDirectoryMode) return false;
    return state.picker.showAll || entry.is_video;
  });
  $("pickerCount").textContent = `${entries.length} item${entries.length === 1 ? "" : "s"}`;

  if (!entries.length) {
    const empty = document.createElement("div");
    empty.className = "picker-empty";
    if (isDirectoryMode) {
      empty.textContent = "No subfolders in this folder. Use “Use this folder” to choose it.";
    } else {
      const hasFiles = (data.entries || []).some((entry) => entry.kind === "file");
      empty.textContent = hasFiles
        ? "No matching video files here. Tick “Show all file types” or open another folder."
        : "This folder is empty.";
    }
    list.appendChild(empty);
    return;
  }

  entries.forEach((entry) => {
    const item = document.createElement("button");
    item.type = "button";
    item.className = `picker-item ${entry.kind === "directory" ? "directory" : "file"}`;
    item.setAttribute("role", "option");
    const icon = document.createElement("span");
    icon.className = "picker-icon";
    icon.setAttribute("aria-hidden", "true");
    icon.textContent = entry.kind === "directory" ? "📁" : "▶";
    const name = document.createElement("span");
    name.className = "picker-name";
    name.textContent = entry.name;
    const meta = document.createElement("span");
    meta.className = "picker-meta";
    meta.textContent =
      entry.kind === "directory"
        ? "Folder"
        : `${entry.size_label || formatBytes(entry.size_bytes)}${entry.is_video ? "" : " · other"}`;
    item.append(icon, name, meta);
    if (entry.kind === "directory") {
      item.title = `Open ${entry.path}`;
      item.addEventListener("click", () => navigatePicker(entry.path));
    } else {
      item.title = entry.path;
      item.addEventListener("click", () => choosePickerFile(entry));
    }
    list.appendChild(item);
  });
}

function choosePickerFile(entry) {
  closePicker();
  $("inputPath").value = entry.path;
  inspectInput(entry.path);
}

function goToPickerPath() {
  const path = $("pickerPath").value.trim();
  if (!path) return;
  navigatePicker(path);
}

async function usePickerFolder() {
  if (!state.picker.data) return;
  $("outputDir").value = state.picker.data.current;
  state.outputTouched = true;
  persistOutputDir();
  closePicker();
  updateStartState();
  if (state.metadata) updatePlan();
}

function showPickerError(message) {
  $("pickerError").textContent = message;
  $("pickerError").classList.remove("hidden");
}

function hidePickerError() {
  $("pickerError").classList.add("hidden");
  $("pickerError").textContent = "";
}

// ---------------------------------------------------------------------------
// Formatting helpers
// ---------------------------------------------------------------------------

function persistOutputDir() {
  safeStorageSet("shoo-cut.outputDir", $("outputDir").value.trim());
}

function safeStorageGet(key) {
  try {
    return window.localStorage.getItem(key);
  } catch (_) {
    return null;
  }
}

function safeStorageSet(key, value) {
  try {
    window.localStorage.setItem(key, value);
  } catch (_) {
    // Storage can be unavailable in hardened browser contexts.
  }
}

function formatDuration(seconds) {
  const value = Number(seconds);
  if (!Number.isFinite(value) || value < 0) return "—";
  const total = Math.round(value);
  const hours = Math.floor(total / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  const secs = total % 60;
  if (hours) return `${hours}h ${String(minutes).padStart(2, "0")}m ${String(secs).padStart(2, "0")}s`;
  if (minutes) return `${minutes}m ${String(secs).padStart(2, "0")}s`;
  return `${secs}s`;
}

function formatElapsed(seconds) {
  const value = Math.max(0, Number(seconds) || 0);
  if (value < 60) return `${value.toFixed(value < 10 ? 1 : 0)}s`;
  const total = Math.round(value);
  const hours = Math.floor(total / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  const secs = total % 60;
  if (hours) return `${hours}h ${String(minutes).padStart(2, "0")}m`;
  return `${minutes}m ${String(secs).padStart(2, "0")}s`;
}

function formatBytes(bytes) {
  const value = Number(bytes);
  if (!Number.isFinite(value) || value < 0) return "unknown";
  if (value < 1024) return `${Math.round(value)} B`;
  const units = ["KB", "MB", "GB", "TB"];
  let size = value / 1024;
  let index = 0;
  while (size >= 1024 && index < units.length - 1) {
    size /= 1024;
    index += 1;
  }
  return `${size.toFixed(1)} ${units[index]}`;
}

function trimNumber(value) {
  const number = Number(value);
  if (!Number.isFinite(number)) return "?";
  return String(Math.round(number * 1000) / 1000);
}

function basename(path) {
  if (!path) return "";
  const clean = String(path).replace(/[\\/]+$/, "");
  const parts = clean.split(/[\\/]/);
  return parts[parts.length - 1] || clean;
}

function parentPath(path) {
  if (!path) return "";
  const clean = String(path).replace(/[\\/]+$/, "");
  const index = Math.max(clean.lastIndexOf("/"), clean.lastIndexOf("\\"));
  if (index < 0) return "";
  if (index === 0) return "/";
  return clean.slice(0, index);
}

function relativeTime(iso) {
  if (!iso) return "";
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return "";
  const seconds = Math.round((Date.now() - date.getTime()) / 1000);
  if (seconds < 45) return "just now";
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m ago`;
  if (seconds < 86400) return `${Math.floor(seconds / 3600)}h ago`;
  return date.toLocaleDateString();
}
