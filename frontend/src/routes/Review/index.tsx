import { useNavigate, useParams } from "react-router-dom";
import Breadcrumbs from "../../components/Breadcrumbs";
import Spinner from "../../components/Spinner";
import useRequest from "../../hooks/request";
import KubernetesRequestDisplay from "./KubernetesRequestDisplay";
import DatasourceRequestDisplay from "./DatasourceRequestDisplay";
import DatasourceRequestActions from "./DatasourceRequestActions";
import KubernetesRequestActions from "./KubernetesRequestActions";
import RequestSidebar from "./RequestSidebar";
import ActivityTimeline from "./ActivityTimeline";
import NotAuthorized from "../../components/NotAuthorized";
import AiQueryReviewPanel from "./AiQueryReviewPanel";
import { hasPermission } from "../../api/Permissions";
import { useState } from "react";

interface RequestReviewParams {
  requestId: string;
}

function RequestReview() {
  const params = useParams() as unknown as RequestReviewParams;
  const {
    request,
    sendReview,
    execute,
    cancelQuery,
    closeRequest,
    start,
    updateRequest,
    retryAiReview,
    overrideAiReview,
    results,
    kubernetesResults,
    dataLoading,
    executionError,
    loading,
    proxyResponse,
  } = useRequest(params.requestId);

  const navigate = useNavigate();
  const [editStatementRequested, setEditStatementRequested] = useState(false);

  const run = async (explain?: boolean, dryRun?: boolean) => {
    if (request?.type === "SingleExecution") {
      await execute(explain || false, dryRun || false);
    } else {
      void navigate(`/requests/${request?.id}/session`);
    }
  };

  const showAiReview =
    request?._type === "DATASOURCE" &&
    !!request.aiReviewMode &&
    request.aiReviewMode !== "DISABLED";
  const aiReviewStarting = showAiReview && request.aiReview == null;

  return (
    <div>
      {(loading && <Spinner size="lg" page />) ||
        (request && (
          <div className="m-auto mt-10 max-w-5xl">
            <Breadcrumbs
              items={[
                { label: "Requests", to: "/requests" },
                { label: request.title },
              ]}
            />
            <h1 className="my-2 text-3xl">{request?.title}</h1>
            <div className="flex flex-col gap-6 md:flex-row md:items-start">
              <RequestSidebar request={request} sendReview={sendReview}>
                {request._type === "DATASOURCE" ? (
                  <DatasourceRequestActions
                    request={request}
                    runQuery={run}
                    cancelQuery={cancelQuery}
                    startServer={start}
                  />
                ) : (
                  <KubernetesRequestActions request={request} runQuery={run} />
                )}
              </RequestSidebar>
              <div className="min-w-0 flex-1">
                {request._type === "DATASOURCE" ? (
                  <DatasourceRequestDisplay
                    request={request}
                    updateRequest={updateRequest}
                    results={results}
                    dataLoading={dataLoading}
                    executionError={executionError}
                    proxyResponse={proxyResponse}
                    editStatementRequested={editStatementRequested}
                    onEditStatementHandled={() =>
                      setEditStatementRequested(false)
                    }
                  ></DatasourceRequestDisplay>
                ) : (
                  <KubernetesRequestDisplay
                    request={request}
                    updateRequest={updateRequest}
                    results={kubernetesResults}
                    dataLoading={dataLoading}
                    executionError={executionError}
                    proxyResponse={proxyResponse}
                  ></KubernetesRequestDisplay>
                )}
                {showAiReview && (
                  <div className="mt-4">
                    <AiQueryReviewPanel
                      review={request.aiReview}
                      override={request.aiReviewOverride}
                      starting={aiReviewStarting}
                      canOverride={hasPermission(
                        request.permissions,
                        "execution_request:override_ai_review",
                      )}
                      onRetry={async () => {
                        await retryAiReview();
                      }}
                      onOverride={async (reason) => {
                        await overrideAiReview(reason);
                      }}
                      onEditStatement={() => setEditStatementRequested(true)}
                    />
                  </div>
                )}
                <div className="mt-3 w-full border-b border-slate-300 dark:border-slate-700"></div>
                <ActivityTimeline
                  request={request}
                  sendReview={sendReview}
                  closeRequest={closeRequest}
                />
              </div>
            </div>
          </div>
        )) || (
          <div className="m-auto mt-10 max-w-3xl">
            <NotAuthorized
              resource="this request"
              message="It may not exist, or your role has no access to its connection."
            />
          </div>
        )}
    </div>
  );
}

export default RequestReview;
