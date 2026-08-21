import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, it, expect, vi } from "vitest";
import AiQueryReviewPanel from "./AiQueryReviewPanel";
import { AiReviewAttempt, AiReviewOverride } from "../../api/ExecutionRequestApi";

const baseAttempt = (
  overrides: Partial<AiReviewAttempt> & Pick<AiReviewAttempt, "status">,
): AiReviewAttempt => ({
  findings: [],
  createdAt: new Date("2026-08-21T10:00:00Z"),
  summary: null,
  suggestedSql: null,
  model: "test-model",
  promptPolicyVersion: "v1",
  errorCategory: null,
  completedAt: new Date("2026-08-21T10:01:00Z"),
  ...overrides,
});

describe("AiQueryReviewPanel", () => {
  it("shows starting state when review has not been created yet", () => {
    render(<AiQueryReviewPanel review={null} starting />);

    expect(screen.getByTestId("ai-review-panel")).toBeInTheDocument();
    expect(screen.getByTestId("ai-review-starting")).toBeInTheDocument();
    expect(screen.getByText(/AI review starting/i)).toBeInTheDocument();
  });

  it("shows a pending spinner while the AI review is in progress", () => {
    render(
      <AiQueryReviewPanel
        review={baseAttempt({
          status: "PENDING",
          completedAt: null,
        })}
      />,
    );

    expect(screen.getByTestId("ai-review-panel")).toBeInTheDocument();
    expect(screen.getByTestId("ai-review-pending")).toBeInTheDocument();
    expect(screen.getByText(/AI review in progress/i)).toBeInTheDocument();
  });

  it("lists findings, fixes, and an edit CTA when rejected", async () => {
    const onEditStatement = vi.fn();
    render(
      <AiQueryReviewPanel
        review={baseAttempt({
          status: "REJECTED",
          summary: "Drop without WHERE is unsafe",
          findings: [
            {
              severity: "BLOCKER",
              category: "destructive",
              explanation: "DELETE without a WHERE clause",
              fix: "Add a WHERE clause limiting the rows",
            },
          ],
        })}
        onEditStatement={onEditStatement}
      />,
    );

    expect(screen.getByText(/rejected/i)).toBeInTheDocument();
    expect(
      screen.getByText("DELETE without a WHERE clause"),
    ).toBeInTheDocument();
    expect(
      screen.getByText("Add a WHERE clause limiting the rows"),
    ).toBeInTheDocument();

    await userEvent.click(screen.getByTestId("ai-review-edit-statement"));
    expect(onEditStatement).toHaveBeenCalledTimes(1);
  });

  it("shows retry when failed and override when permitted", async () => {
    const onRetry = vi.fn().mockResolvedValue(undefined);
    const onOverride = vi.fn().mockResolvedValue(undefined);

    render(
      <AiQueryReviewPanel
        review={baseAttempt({
          status: "FAILED",
          summary: null,
          errorCategory: "PROVIDER_ERROR",
        })}
        canOverride
        onRetry={onRetry}
        onOverride={onOverride}
      />,
    );

    expect(screen.getByText(/failed/i)).toBeInTheDocument();
    await userEvent.click(screen.getByTestId("ai-review-retry"));
    expect(onRetry).toHaveBeenCalledTimes(1);

    await userEvent.type(
      screen.getByTestId("ai-review-override-reason"),
      "Provider outage; reviewed manually",
    );
    await userEvent.click(screen.getByTestId("ai-review-override"));
    expect(onOverride).toHaveBeenCalledWith(
      "Provider outage; reviewed manually",
    );
  });

  it("does not offer override without permission on a failed review", () => {
    render(
      <AiQueryReviewPanel
        review={baseAttempt({
          status: "FAILED",
          errorCategory: "TIMEOUT",
        })}
        canOverride={false}
        onRetry={vi.fn()}
      />,
    );

    expect(screen.getByTestId("ai-review-retry")).toBeInTheDocument();
    expect(
      screen.queryByTestId("ai-review-override"),
    ).not.toBeInTheDocument();
  });

  it("lists notes for approved-with-notes and shows an overridden banner", () => {
    const override: AiReviewOverride = {
      reason: "Manual review completed",
      createdAt: new Date("2026-08-21T11:00:00Z"),
      actorName: "Admin",
    };

    render(
      <AiQueryReviewPanel
        review={baseAttempt({
          status: "APPROVED_WITH_NOTES",
          summary: "Looks mostly fine",
          findings: [
            {
              severity: "WARNING",
              category: "performance",
              explanation: "Missing index hint",
              fix: "Consider adding an index",
            },
          ],
        })}
        override={override}
      />,
    );

    expect(screen.getByText(/approved with notes/i)).toBeInTheDocument();
    expect(screen.getByText("Missing index hint")).toBeInTheDocument();
    expect(screen.getByTestId("ai-review-overridden")).toBeInTheDocument();
    expect(screen.getByText(/Manual review completed/)).toBeInTheDocument();
  });

  it("does not render model HTML as DOM nodes", () => {
    render(
      <AiQueryReviewPanel
        review={baseAttempt({
          status: "APPROVED",
          summary: '<img src=x onerror="alert(1)">safe summary',
        })}
      />,
    );

    const panel = screen.getByTestId("ai-review-panel");
    expect(panel.querySelector("img")).toBeNull();
    expect(screen.getByText(/safe summary/)).toBeInTheDocument();
  });
});
