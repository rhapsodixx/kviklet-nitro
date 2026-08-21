function statusLabel(status: string): string {
  switch (status) {
    case "PENDING":
      return "AI pending";
    case "APPROVED":
      return "AI approved";
    case "APPROVED_WITH_NOTES":
      return "AI notes";
    case "REJECTED":
      return "AI rejected";
    case "FAILED":
      return "AI failed";
    default:
      return `AI ${status}`;
  }
}

function statusClasses(status: string): string {
  switch (status) {
    case "APPROVED":
    case "APPROVED_WITH_NOTES":
      return "bg-lime-50 text-lime-700 ring-lime-600/20 dark:bg-lime-400/10 dark:text-lime-400 dark:ring-lime-400/20";
    case "REJECTED":
    case "FAILED":
      return "bg-red-50 text-red-700 ring-red-600/20 dark:bg-red-400/10 dark:text-red-400 dark:ring-red-400/20";
    case "PENDING":
      return "bg-yellow-50 text-yellow-700 ring-yellow-600/20 dark:bg-yellow-400/10 dark:text-yellow-400 dark:ring-yellow-400/20";
    default:
      return "bg-slate-50 text-slate-600 ring-slate-500/20 dark:bg-slate-800 dark:text-slate-300 dark:ring-slate-500/20";
  }
}

function AiReviewBadge({
  status,
  overridden,
}: {
  status: string | null | undefined;
  overridden?: boolean;
}) {
  if (!status) {
    return null;
  }

  return (
    <div
      className={`w-fit rounded-md px-2 py-1 text-xs font-medium ring-1 ring-inset ${statusClasses(
        status,
      )}`}
      data-testid="ai-review-badge"
      title={overridden ? "AI review overridden" : undefined}
    >
      {overridden ? `${statusLabel(status)} (overridden)` : statusLabel(status)}
    </div>
  );
}

export default AiReviewBadge;
