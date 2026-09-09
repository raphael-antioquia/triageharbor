import {
  IncidentContractError,
  parseIncidentSummaries,
  type IncidentSummary,
} from "./incidents";

export async function fetchIncidentSummaries(
  projectId: string,
  signal?: AbortSignal,
): Promise<IncidentSummary[]> {
  const response = await fetch(
    `/api/projects/${encodeURIComponent(projectId)}/incidents`,
    {
      headers: {
        Accept: "application/json",
      },
      signal,
    },
  );

  if (!response.ok) {
    throw new Error(
      `Incident request failed with HTTP status ${response.status}`,
    );
  }

  let body: unknown

  try {
    body = await response.json()
  } catch (error: unknown) {
    if (error instanceof DOMException && error.name === 'AbortError') {
      throw error
    }

    throw new IncidentContractError(
      'Incident response was not valid JSON',
    )
  }

  return parseIncidentSummaries(body);
}