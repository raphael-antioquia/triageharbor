/**
 * Frontend contract for the backend IncidentSummary response.
 *
 * Unknown incident statuses intentionally fail closed. If the backend adds a
 * status, this contract must be updated in the same compatible deployment
 * before responses containing that status are expected.
 *
 * Contract errors must not include whole incident objects or arbitrary string
 * field values. Incident titles are event-derived and may contain sensitive
 * data before the required scrubbing boundary exists.
 */

export type IncidentStatus = "open" | "resolved";

export type IncidentSummary = {
  id: string;
  title: string;
  status: IncidentStatus;
  occurrenceCount: number;
  firstSeenAt: string;
  lastSeenAt: string;
};

export class IncidentContractError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "IncidentContractError";
  }
}

function describeType(value: unknown): string {
  if (value === null) {
    return "null";
  }

  if (Array.isArray(value)) {
    return "array";
  }

  return typeof value;
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function parseStringField(
  incident: Record<string, unknown>,
  field: string,
  index: number,
): string {
  const value = incident[field];

  if (typeof value !== "string") {
    throw new IncidentContractError(
      `Malformed incident at index ${index}: expected string ${field}, got ${describeType(value)}`,
    );
  }

  return value;
}

function parseStatus(
  incident: Record<string, unknown>,
  index: number,
): IncidentStatus {
  const value = incident.status;

  if (value === "open" || value === "resolved") {
    return value;
  }

  const actual =
    typeof value === "string" ? JSON.stringify(value) : describeType(value);

  throw new IncidentContractError(
    `Malformed incident at index ${index}: expected status "open" | "resolved", got ${actual}`,
  );
}

function parseOccurrenceCount(
  incident: Record<string, unknown>,
  index: number,
): number {
  const value = incident.occurrenceCount;

  if (typeof value !== "number") {
    throw new IncidentContractError(
      `Malformed incident at index ${index}: expected number occurrenceCount, got ${describeType(value)}`,
    );
  }

  if (!Number.isInteger(value) || value <= 0) {
    throw new IncidentContractError(
      `Malformed incident at index ${index}: expected positive integer occurrenceCount, got ${String(value)}`,
    );
  }

  return value;
}

function parseTimestamp(
  incident: Record<string, unknown>,
  field: "firstSeenAt" | "lastSeenAt",
  index: number,
): string {
  const value = incident[field];

  if (typeof value !== "string") {
    throw new IncidentContractError(
      `Malformed incident at index ${index}: expected string ${field}, got ${describeType(value)}`,
    );
  }

  if (Number.isNaN(new Date(value).getTime())) {
    throw new IncidentContractError(
      `Malformed incident at index ${index}: expected parseable timestamp ${field}`,
    );
  }

  return value;
}

function parseIncident(value: unknown, index: number): IncidentSummary {
  if (!isObject(value)) {
    throw new IncidentContractError(
      `Malformed incident at index ${index}: expected object, got ${describeType(value)}`,
    );
  }

  return {
    id: parseStringField(value, "id", index),
    title: parseStringField(value, "title", index),
    status: parseStatus(value, index),
    occurrenceCount: parseOccurrenceCount(value, index),
    firstSeenAt: parseTimestamp(value, "firstSeenAt", index),
    lastSeenAt: parseTimestamp(value, "lastSeenAt", index),
  };
}

export function parseIncidentSummaries(value: unknown): IncidentSummary[] {
  if (!Array.isArray(value)) {
    throw new IncidentContractError(
      `Malformed incident response: expected array, got ${describeType(value)}`,
    );
  }

  return value.map(parseIncident);
}
