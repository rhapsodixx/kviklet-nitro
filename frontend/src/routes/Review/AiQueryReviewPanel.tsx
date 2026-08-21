import ReactMarkdown from "react-markdown";
import {
  AiReviewAttempt,
  AiReviewFinding,
  AiReviewOverride,
} from "../../api/ExecutionRequestApi";
import Button from "../../components/Button";
import Spinner from "../../components/Spinner";
import { WarningBanner } from "../../components/Alert";
import { componentMap } from "./components/Highlighter";
import { useState } from "react";

function statusLabel(status: string): string {
  switch (status) {
    case "PENDING":
      return "Pending";
    case "APPROVED":
      return "Approved";
    case "APPROVED_WITH_NOTES":
      return "Approved with notes";
    case "REJECTED":
      return "Rejected";
    case "FAILED":
      return "Failed";
    default:
      return status;
  }
}

function statusTone(status: string): string {
  switch (status) {
    case "APPROVED":
    case "APPROVED_WITH_NOTES":
      return "text-lime-700 dark:text-lime-400";
    case "REJECTED":
    case "FAILED":
      return "text-red-700 dark:text-red-400";
    case "PENDING":
      return "text-yellow-700 dark:text-yellow-400";
    default:
      return "text-slate-700 dark:text-slate-300";
  }
}

function FindingList({ findings }: { findings: AiReviewFinding[] }) {
  if (findings.length === 0) {
    return null;
  }
  return (
    <ul className="mt-3 space-y-3" data-testid="ai-review-findings">
      {findings.map((finding, index) => (
        <li
          key={`${finding.category}-${index}`}
          className="rounded-md border border-slate-200 p-3 dark:border-slate-700"
        >
          <div className="text-xs font-medium uppercase tracking-wide text-slate-500 dark:text-slate-400">
            {finding.severity} · {finding.category}
          </div>
          <div className="mt-1 text-sm text-slate-800 dark:text-slate-200">
            <ReactMarkdown components={componentMap}>
              {finding.explanation}
            </ReactMarkdown>
          </div>
          {finding.fix && (
            <div className="mt-2 text-sm text-slate-600 dark:text-slate-300">
              <span className="font-medium">Fix: </span>
              <ReactMarkdown components={componentMap}>
                {finding.fix}
              </ReactMarkdown>
            </div>
          )}
        </li>
      ))}
    </ul>
  );
}

function AiQueryReviewPanel({
  review,
  override,
  canOverride = false,
  starting = false,
  onRetry,
  onOverride,
  onEditStatement,
}: {
  review: AiReviewAttempt | null | undefined;
  override?: AiReviewOverride | null;
  canOverride?: boolean;
  /** True when mode is enabled but no attempt exists yet (enqueue in flight). */
  starting?: boolean;
  onRetry?: () => Promise<void>;
  onOverride?: (reason: string) => Promise<void>;
  onEditStatement?: () => void;
}) {
  const [overrideReason, setOverrideReason] = useState("");
  const [submitting, setSubmitting] = useState(false);

  if (!review && starting) {
    return (
      <section
        className="mb-4 rounded-md border border-slate-200 p-4 dark:border-slate-700 dark:bg-slate-900/40"
        data-testid="ai-review-panel"
      >
        <div className="flex items-center justify-between gap-2">
          <h2 className="text-sm font-semibold uppercase tracking-wider text-slate-500 dark:text-slate-400">
            AI Query Review
          </h2>
          <span
            className={`text-sm font-medium ${statusTone("PENDING")}`}
            data-testid="ai-review-status"
          >
            {statusLabel("PENDING")}
          </span>
        </div>
        <div
          className="mt-4 flex items-center gap-3 text-sm text-slate-600 dark:text-slate-300"
          data-testid="ai-review-starting"
        >
          <Spinner size="sm" />
          <span>AI review starting…</span>
        </div>
      </section>
    );
  }

  if (!review) {
    return null;
  }

  const handleRetry = async () => {
    if (!onRetry || submitting) {
      return;
    }
    setSubmitting(true);
    try {
      await onRetry();
    } finally {
      setSubmitting(false);
    }
  };

  const handleOverride = async () => {
    if (!onOverride || submitting || !overrideReason.trim()) {
      return;
    }
    setSubmitting(true);
    try {
      await onOverride(overrideReason.trim());
      setOverrideReason("");
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <section
      className="mb-4 rounded-md border border-slate-200 p-4 dark:border-slate-700 dark:bg-slate-900/40"
      data-testid="ai-review-panel"
    >
      <div className="flex items-center justify-between gap-2">
        <h2 className="text-sm font-semibold uppercase tracking-wider text-slate-500 dark:text-slate-400">
          AI Query Review
        </h2>
        <span
          className={`text-sm font-medium ${statusTone(review.status)}`}
          data-testid="ai-review-status"
        >
          {statusLabel(review.status)}
        </span>
      </div>

      {override && (
        <WarningBanner
          className="mt-3"
          data-testid="ai-review-overridden"
        >
          AI review overridden
          {override.actorName ? ` by ${override.actorName}` : ""}
          {override.reason ? `: ${override.reason}` : ""}
        </WarningBanner>
      )}

      {review.status === "PENDING" && (
        <div
          className="mt-4 flex items-center gap-3 text-sm text-slate-600 dark:text-slate-300"
          data-testid="ai-review-pending"
        >
          <Spinner size="sm" />
          <span>AI review in progress…</span>
        </div>
      )}

      {review.status !== "PENDING" && review.summary && (
        <div className="mt-3 text-sm text-slate-800 dark:text-slate-200">
          <ReactMarkdown components={componentMap}>
            {review.summary}
          </ReactMarkdown>
        </div>
      )}

      {(review.status === "APPROVED_WITH_NOTES" ||
        review.status === "REJECTED") && (
        <FindingList findings={review.findings} />
      )}

      {review.status === "APPROVED" && review.findings.length > 0 && (
        <FindingList findings={review.findings} />
      )}

      {review.status === "REJECTED" && onEditStatement && (
        <div className="mt-4">
          <Button
            variant="primary"
            onClick={onEditStatement}
            dataTestId="ai-review-edit-statement"
          >
            Edit statement
          </Button>
        </div>
      )}

      {review.status === "FAILED" && (
        <div className="mt-4 space-y-3">
          {review.errorCategory && (
            <p className="text-sm text-slate-600 dark:text-slate-300">
              Error: {review.errorCategory}
            </p>
          )}
          {onRetry && (
            <Button
              onClick={() => void handleRetry()}
              dataTestId="ai-review-retry"
              variant={submitting ? "disabled" : "primary"}
            >
              Retry
            </Button>
          )}
          {canOverride && onOverride && (
            <div className="space-y-2">
              <label
                htmlFor="ai-review-override-reason"
                className="block text-sm font-medium text-slate-700 dark:text-slate-200"
              >
                Override reason
              </label>
              <textarea
                id="ai-review-override-reason"
                data-testid="ai-review-override-reason"
                className="block w-full rounded-md border border-slate-300 p-2 text-sm dark:border-slate-700 dark:bg-slate-900 dark:text-slate-50"
                rows={2}
                value={overrideReason}
                onChange={(e) => setOverrideReason(e.target.value)}
              />
              <Button
                onClick={() => void handleOverride()}
                dataTestId="ai-review-override"
                variant={
                  submitting || !overrideReason.trim() ? "disabled" : "danger"
                }
              >
                Override AI review
              </Button>
            </div>
          )}
        </div>
      )}
    </section>
  );
}

export default AiQueryReviewPanel;
